"""阶段六 C 组的契约与行为探针。

覆盖四项改动里**只有在真实容器上才能验证**的部分：

1. 分页：`GET .../conversations` 与 `GET .../conversations/{id}/messages`
   现在返回 `{items, page, size, totalElements, totalPages, hasNext}` 而不是数组。
2. 分页参数归一化：`?size=1000000` 会被压到 100，`?page=-3` 会被归零。
3. 登录限流：同一邮箱连续失败 5 次后返回 429。
4. LLM 接口限流：超额请求在**进入业务逻辑之前**就被拒（用不存在的会话探测，
   故意让它走 403 分支 —— 403 就说明限额已经扣掉、但还没轮到查库）。

为什么这些要单独跑一遍：单元测试用的是 mock 出来的仓储和缓存，
验证不了「Spring 真的把参数绑上了」「拦截器与 Controller 的顺序真的是这样」。
本脚本只读（除了建一个探测用知识库和几条会话），跑完可以删。

**它留下的东西**：探测账号 `c-group-probe@example.com`、一个名字含「C 组探针」的知识库、
一条会话，以及若干 `ratelimit-probe-<时间戳>` 这类不会被真正使用的限流探测地址。

**副作用与幂等性**：
- 第 6 步会把探测账号的 ask 配额（20 次 / 分钟）刷满，所以**同一个账号 1 分钟内不能连跑两次**。
  脚本检测到这种情况会打印 `[SKIP]` 并跳过，不会报假失败。
- 第 5 步的登录限流探测每次运行都用**全新的邮箱**，所以可以反复跑、也不会锁住任何真实账号。

用法：
    python scripts/verify_c_group.py
    python scripts/verify_c_group.py --email <已存在邮箱> --password <密码>
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"
DEFAULT_EMAIL = "c-group-probe@example.com"
DEFAULT_PASSWORD = "c-group-probe-2026"

# 和后端 RateLimitProperties 的默认值保持一致；改了配置要同步改这里
LOGIN_MAX_ATTEMPTS = 5
ASK_MAX_REQUESTS = 20

passed = 0
failed: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed.append(label)
        print(f"  [FAIL] {label}" + (f" -- {detail}" if detail else ""))


def call(method: str, path: str, body: object | None = None, token: str | None = None) -> tuple[int, dict]:
    """返回 (状态码, 响应体)。**不抛异常** —— 本脚本就是要看 4xx。"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    request = urllib.request.Request(f"{BASE_URL}{path}", data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def login_or_register(email: str, password: str) -> tuple[str, int]:
    status, payload = call("POST", "/api/v1/users/login", {"email": email, "password": password})
    if status == 200:
        print(f"已登录：{email}")
        return payload["data"]["accessToken"], payload["data"]["user"]["id"]
    status, payload = call(
        "POST", "/api/v1/users", {"username": email.split("@")[0], "email": email, "password": password}
    )
    if status not in (200, 201):
        raise SystemExit(f"注册失败：HTTP {status} {payload}")
    print(f"已注册：{email}")
    status, payload = call("POST", "/api/v1/users/login", {"email": email, "password": password})
    if status != 200:
        raise SystemExit(f"注册后登录失败：HTTP {status} {payload}")
    return payload["data"]["accessToken"], payload["data"]["user"]["id"]


PROBE_KNOWLEDGE_BASE_NAME = "C 组探针知识库"


def ensure_knowledge_base(token: str, user_id: int) -> int:
    """复用同名的探针知识库；没有才新建。

    之所以要复用而不是每次都建：这个脚本会反复跑（改一次代码验一次），
    每次新建会在库里堆一串同名知识库，跑几次就分不清哪条会话是哪次的了。
    """
    _, payload = call("GET", "/api/v1/workspaces", token=token)
    workspaces = payload["data"]
    if workspaces:
        workspace_id = workspaces[0]["id"]
    else:
        _, payload = call(
            "POST", "/api/v1/workspaces", {"name": "C 组探针工作区", "ownerUserId": user_id}, token=token
        )
        workspace_id = payload["data"]["id"]

    _, payload = call("GET", f"/api/v1/knowledge-bases?workspaceId={workspace_id}", token=token)
    existing = [item for item in payload["data"] if item.get("name") == PROBE_KNOWLEDGE_BASE_NAME]
    if existing:
        print(f"复用已有知识库 {existing[0]['id']}（{PROBE_KNOWLEDGE_BASE_NAME}）")
        return existing[0]["id"]

    _, payload = call(
        "POST",
        "/api/v1/knowledge-bases",
        {"workspaceId": workspace_id, "name": PROBE_KNOWLEDGE_BASE_NAME, "description": "验证分页与限流用，可删"},
        token=token,
    )
    return payload["data"]["id"]


def verify_pagination_shape(token: str, knowledge_base_id: int) -> None:
    print("\n[1] 会话列表：分页外壳")
    status, payload = call("GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations?page=0&size=2", token=token)
    check("HTTP 200", status == 200, f"得到 {status}")
    data = payload.get("data") or {}
    check("data 是对象而不是数组（契约已改为分页外壳）", isinstance(data, dict), f"实际 {type(data).__name__}")
    for field in ("items", "page", "size", "totalElements", "totalPages", "hasNext"):
        check(f"含字段 {field}", field in data)
    check("items 是数组", isinstance(data.get("items"), list))
    check("回显归一化后的 page", data.get("page") == 0, f"实际 {data.get('page')}")
    check("回显归一化后的 size", data.get("size") == 2, f"实际 {data.get('size')}")
    check("hasNext 是布尔", isinstance(data.get("hasNext"), bool))

    print("\n[2] 分页参数归一化（外部可控输入必须收敛）")
    _, huge = call("GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations?page=0&size=1000000", token=token)
    check("size=1000000 被压到上限 100", huge["data"]["size"] == 100, f"实际 {huge['data']['size']}")
    _, negative = call("GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations?page=-3&size=-5", token=token)
    check("page=-3 归零", negative["data"]["page"] == 0, f"实际 {negative['data']['page']}")
    check("size=-5 退回默认 20", negative["data"]["size"] == 20, f"实际 {negative['data']['size']}")
    _, no_params = call("GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations", token=token)
    check("不传参数也能工作（向后兼容）", no_params["data"]["size"] == 20)

    print("\n[3] 会话消息：页号从最新往回数")
    _, created = call("POST", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations", {"title": "C 组探针会话"}, token=token)
    session_id = created["data"]["id"]
    status, payload = call(
        "GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations/{session_id}/messages?page=0&size=5", token=token
    )
    check("HTTP 200", status == 200, f"得到 {status}")
    data = payload.get("data") or {}
    check("消息也返回分页外壳", isinstance(data, dict) and "items" in data)
    check("空会话 totalElements 为 0", data.get("totalElements") == 0, f"实际 {data.get('totalElements')}")
    check("空会话 hasNext 为 false", data.get("hasNext") is False)
    check("空会话 totalPages 为 0", data.get("totalPages") == 0, f"实际 {data.get('totalPages')}")

    print("\n[4] 越界页不能骗前端再翻一次")
    _, beyond = call(
        "GET", f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations/{session_id}/messages?page=99&size=20", token=token
    )
    check("越界页返回空 items", beyond["data"]["items"] == [])
    check("越界页 hasNext 为 false（否则前端会死循环点加载更多）", beyond["data"]["hasNext"] is False)


def verify_login_rate_limit(email: str) -> None:
    print(f"\n[5] 登录限流：同一邮箱连续失败 {LOGIN_MAX_ATTEMPTS} 次后锁定")
    # 每次运行都用**全新的**邮箱：限流是按邮箱记账的，如果沿用固定的探测邮箱，
    # 第二次跑时它已经在上一次的 15 分钟窗口里被锁着，第一步就会直接拿到 429 而误报。
    # 同时也是为了不锁真实账号 —— 这就是为什么必须用一个专门造出来的地址。
    probe_email = f"ratelimit-probe-{int(time.time())}@example.com"
    statuses = []
    for _ in range(LOGIN_MAX_ATTEMPTS):
        status, _ = call("POST", "/api/v1/users/login", {"email": probe_email, "password": "definitely-wrong"})
        statuses.append(status)
    check(
        f"前 {LOGIN_MAX_ATTEMPTS - 1} 次是 401（密码错）",
        statuses[: LOGIN_MAX_ATTEMPTS - 1] == [401] * (LOGIN_MAX_ATTEMPTS - 1),
        f"实际 {statuses}",
    )
    check(f"第 {LOGIN_MAX_ATTEMPTS} 次变成 429（达到上限）", statuses[-1] == 429, f"实际 {statuses}")

    status, payload = call("POST", "/api/v1/users/login", {"email": probe_email, "password": "definitely-wrong"})
    check("锁定期内继续尝试仍是 429", status == 429, f"实际 {status}")
    check("429 的 code 是 RATE_LIMIT_EXCEEDED", payload.get("code") == "RATE_LIMIT_EXCEEDED", f"实际 {payload.get('code')}")
    check("429 的提示语告诉了用户等多久", "minute" in (payload.get("message") or ""), f"实际 {payload.get('message')}")

    # 大小写变体必须落进同一个计数器，否则换个大写就能绕过
    status, _ = call("POST", "/api/v1/users/login", {"email": probe_email.upper(), "password": "definitely-wrong"})
    check("换大小写写法仍然被锁（邮箱已归一化）", status == 429, f"实际 {status}")

    # 别的邮箱不该被连累（注意不能改成大写探测上面那个，否则验的就不是「无关联」了）
    status, _ = call("POST", "/api/v1/users/login", {"email": f"unrelated-{email}", "password": "wrong"})
    check("其他账号不受影响（返回 401 而不是 429）", status == 401, f"实际 {status}")


def verify_ask_rate_limit(token: str, knowledge_base_id: int) -> None:
    print(f"\n[6] LLM 接口限流：配额在业务逻辑之前扣除")
    # 故意打一个不存在的会话：正常应返回 403（无权访问），
    # 所以一旦出现 429 就说明「扣配额」发生在「查会话」之前 —— 正是我们要的顺序。
    # 用这种方式探测不会真的调用模型，零成本。
    path = f"/api/v1/knowledge-bases/{knowledge_base_id}/conversations/999999999/agent-messages"

    # 幂等保护：限流窗口是 1 分钟。1 分钟内连跑两次的话，上一次已经把配额刷满，
    # 这里第一次请求就会是 429。那不是缺陷，是脚本自己的副作用 ——
    # 明确跳过并说清楚，比报一个假的 [FAIL] 强。
    first_status, _ = call("POST", path, {"question": "probe"}, token=token)
    if first_status == 429:
        print(f"  [SKIP] 该账号的 ask 配额尚未过期（1 分钟窗口），本轮跳过。")
        print(f"         上一次运行在 1 分钟内已经用掉了 {ASK_MAX_REQUESTS} 次配额，等一会儿再跑即可。")
        return

    statuses = [first_status]
    for _ in range(ASK_MAX_REQUESTS):
        status, _ = call("POST", path, {"question": "probe"}, token=token)
        statuses.append(status)

    check("前若干次是 403（会话不存在/无权访问）", 403 in statuses, f"实际状态码序列 {statuses}")
    check(f"第 {ASK_MAX_REQUESTS + 1} 次变成 429（配额已用尽）", statuses[-1] == 429, f"实际状态码序列 {statuses}")
    check(
        "扣配额发生在鉴权之后、业务之前（先 403 后 429）",
        statuses[:ASK_MAX_REQUESTS] == [403] * ASK_MAX_REQUESTS,
        f"实际 {statuses}",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="阶段六 C 组契约与行为探针")
    parser.add_argument("--email", default=DEFAULT_EMAIL)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    arguments = parser.parse_args()

    print(f"目标：{BASE_URL}")
    token, user_id = login_or_register(arguments.email, arguments.password)
    knowledge_base_id = ensure_knowledge_base(token, user_id)
    print(f"使用知识库 {knowledge_base_id}")

    verify_pagination_shape(token, knowledge_base_id)
    verify_login_rate_limit(arguments.email)
    verify_ask_rate_limit(token, knowledge_base_id)

    print(f"\n合计：{passed} 项通过，{len(failed)} 项失败")
    if failed:
        for item in failed:
            print(f"  - {item}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
