"""端到端验证：走 HTTP 打后端，确认「注册 → 建库 → 上传 → 解析 → Agent 问答 → 落库」整条链路可用。

用法（在仓库根目录，需先 ``docker compose up``）：

    python scripts/e2e_agent.py

    # 复用已有的账号和知识库，跳过注册/上传（快）
    python scripts/e2e_agent.py --email me@example.com --password xxxxxxxx --knowledge-base-id 31

## 为什么需要它

ai-service 的单元测试和 ``scripts/smoke_agent.py`` 都只覆盖到 Python 侧。但从用户按下回车到
看到回答，中间还隔着一层 Java：后端要拼请求、解析 ai-service 的响应、再把执行轨迹
（``tool`` / ``query`` / ``result_count``）写进 ``conversation_message_agent_step``。
**这一层最容易出问题，而且失败模式很难看** —— 比如工具名拼错、``query`` 超长导致 INSERT 报错，
现象都是「AI 不回答」，但根因层层嵌套，光看日志很难定位。

所以本脚本刻意用**和前端完全相同的接口**跑一遍，并断言执行轨迹真的落库了。

## 它会写入什么

会创建一个新账号、一个新知识库和一份测试文档，并产生一条会话记录。都是新增，不修改任何
已有数据。想清理的话删掉那个知识库即可（自带级联删除）。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"

# 一份带小标题、且含一个可验证事实的测试文档 —— 既能测到 document_outline 的结构还原，
# 也能测到 knowledge_search 是否真的按内容作答（而不是复述问题）。
TEST_DOCUMENT = """# 巡检机器人运维手册

本手册面向现场运维人员，用于日常巡检与故障处置。

## 日常巡检

每天检查电量、网络与固件版本三项。电量低于 30% 必须回桩充电。

## 故障上报

连续三次上报失败即判定为链路故障，需要在 15 分钟内提交工单。

### 工单字段

工单必须填写设备编号、故障时间与现场照片。
"""

QUESTIONS = [
    # 枚举类问题：检索答不出来（没有「有哪些」这个语义），必须选 list_documents
    "这个知识库一共收录了哪些文档？只列文件名。",
    # 事实类问题：必须走检索，答案唯一可验证（30%）
    "巡检机器人电量低于多少必须回桩充电？",
    # 结构 + 检索的组合：模型应先看目录，再定位到具体小节
    "故障上报这一节里，工单需要填写哪些字段？",
]

# result_count 是「这次工具调用拿回了多少条结果」，单位随工具而变。
# 前端执行轨迹面板用的是同一套量词（frontend/src/views/AgentView.vue 的 RESULT_UNITS）。
RESULT_UNITS = {
    "knowledge_search": "片",
    "list_documents": "篇",
    "document_outline": "小节",
}


class Failure(Exception):
    pass


def request(method: str, path: str, body: object | None = None, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    http_request = urllib.request.Request(f"{BASE_URL}{path}", data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(http_request, timeout=120) as response:
            raw = response.read().decode()
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise Failure(f"{method} {path} -> HTTP {error.code}: {detail}") from error
    return json.loads(raw) if raw else {}


def upload(path: str, token: str, filename: str, content: bytes, content_type: str) -> dict:
    boundary = "----e2e-ai-copilot-boundary"
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    http_request = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(http_request, timeout=120) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raise Failure(f"POST {path} -> HTTP {error.code}: {error.read().decode(errors='replace')}") from error


def login_or_register(email: str, password: str) -> tuple[str, int]:
    """登录；账号不存在时先注册再登录。返回 ``(access token, 用户 id)``。

    用「先试登录」的顺序，避免依赖注册接口的具体错误码。
    """
    try:
        data = request("POST", "/api/v1/users/login", {"email": email, "password": password})["data"]
        print(f"已登录：{email}")
    except Failure:
        request("POST", "/api/v1/users", {"username": email.split("@")[0], "email": email, "password": password})
        print(f"已注册并登录：{email}")
        data = request("POST", "/api/v1/users/login", {"email": email, "password": password})["data"]
    return data["accessToken"], data["user"]["id"]


def ensure_knowledge_base(token: str, user_id: int, name: str) -> tuple[int, int]:
    """建一个知识库并上传一份测试文档，返回 ``(知识库 id, 文档 id)``。

    注意：**注册不会自动创建默认工作区**，所以新账号这里要先自己建一个。
    """
    workspaces = request("GET", "/api/v1/workspaces", token=token)["data"]
    if workspaces:
        workspace_id = workspaces[0]["id"]
    else:
        workspace_id = request(
            "POST", "/api/v1/workspaces", {"name": "e2e 工作区", "ownerUserId": user_id}, token=token
        )["data"]["id"]
        print(f"已创建工作区 {workspace_id}")

    created = request("POST", "/api/v1/knowledge-bases", {
        "workspaceId": workspace_id, "name": name, "description": "端到端验证用（可删除）",
    }, token=token)["data"]
    knowledge_base_id = created["id"]
    print(f"已创建工作区 {workspace_id} 下的知识库 {knowledge_base_id}（{name}）")

    uploaded = upload(
        f"/api/v1/knowledge-bases/{knowledge_base_id}/documents/upload",
        token, "inspection-robot.md", TEST_DOCUMENT.encode(), "text/markdown",
    )["data"]
    document_id = uploaded["id"]
    print(f"已上传文档 {document_id}：inspection-robot.md")
    return knowledge_base_id, document_id


def wait_for_processing(token: str, knowledge_base_id: int, document_id: int, timeout: int = 180) -> str:
    """轮询到解析完成。上传接口是异步的，直接提问会检索到空索引。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = request(
            "GET",
            f"/api/v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}/processing-status",
            token=token,
        )["data"]["status"]
        if status == "COMPLETED":
            print("文档解析完成，索引就绪")
            return status
        if status in {"FAILED", "DISABLED"}:
            raise Failure(f"文档解析没有成功：{status}")
        time.sleep(2)
    raise Failure(f"等待解析超时（{timeout}s）")


def ask(token: str, knowledge_base_id: int, session_id: int, question: str) -> dict:
    return request(
        "POST",
        f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations/{session_id}/agent-messages",
        {"question": question},
        token=token,
    )["data"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Agent 端到端验证")
    parser.add_argument("--email", default="")
    parser.add_argument("--password", default="e2e-password-2026")
    parser.add_argument("--knowledge-base-id", type=int, default=0)
    arguments = parser.parse_args()

    email = arguments.email or f"e2e_agent_{time.strftime('%Y%m%d%H%M%S')}@example.com"

    try:
        token, user_id = login_or_register(email, arguments.password)

        if arguments.knowledge_base_id:
            knowledge_base_id = arguments.knowledge_base_id
            print(f"复用知识库 {knowledge_base_id}")
        else:
            knowledge_base_id, document_id = ensure_knowledge_base(
                token, user_id, f"e2e-agent-{time.strftime('%H%M%S')}"
            )
            wait_for_processing(token, knowledge_base_id, document_id)

        session = request(
            "POST", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations",
            {"title": "Agent 端到端验证"}, token=token,
        )["data"]
        session_id = session["id"]

        failures = 0
        for question in QUESTIONS:
            reply = ask(token, knowledge_base_id, session_id, question)
            message = reply["assistantMessage"]
            steps = message.get("agentSteps") or []
            print(f"\n{'=' * 72}\n提问：{question}\n{'=' * 72}")
            for step in steps:
                unit = RESULT_UNITS.get(step["tool"], "条")
                print(f"  · {step['tool']}  |  {step['query']}  |  {step['resultCount']} {unit}")
            print(f"  引用来源：{len(message.get('sources') or [])} 条")
            print(f"\n回答：\n{message['content']}\n")
            if not steps:
                print("⚠️  执行轨迹为空 —— 要么走了降级路径，要么没落库", file=sys.stderr)
                failures += 1

        return 1 if failures else 0
    except Failure as error:
        print(f"\n❌ 端到端失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
