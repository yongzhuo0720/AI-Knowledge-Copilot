"""把 docs/rag-eval/corpus 下的语料上传成两个可复现的评估知识库。

## 为什么需要它

评估结果要能被信任，前提是「这份语料是怎么进去的」是可复现的。
之前那一次评估用的是手工建出来的知识库（KB 31），归属人和密码都没人记得 ——
换台机器、或者容器被重建之后，那份语料就重建不出来了，报告里的数字也就没法核对。

所以这个脚本做四件事：**固定账号 → 固定知识库名 → 幂等上传 → 等解析完成**。
重跑它不会产生重复文档，也不会新建知识库。

## 为什么分成两个知识库

- **文本语料**：10 篇 .md，是主评估集 `questions.json` 的语料。单独放一个库，
  它的指标才能和历史报告直接对比（语料没变，指标才可比）。
- **新格式语料**：xlsx / pptx / csv / html 各一份，对应 `questions-new-formats.json`。
  单独放一个库，是为了让「新格式能不能被解析、入库、检索到」这件事**不含排序噪声**：
  库里只有 4 篇文档，任何 MISS 都只能是解析或入库的问题，不可能是「被别的文档挤掉了」。

用法：
    python scripts/build_eval_knowledge_bases.py
    python scripts/build_eval_knowledge_bases.py --base-url http://localhost:8080
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CORPUS = REPO_ROOT / "docs" / "rag-eval" / "corpus"

EVAL_EMAIL = "rag-eval@example.com"
EVAL_PASSWORD = "rag-eval-password-2026"
EVAL_WORKSPACE = "RAG 评估集"

TEXT_SET = {
    "name": "RAG 评估集 · 文本语料",
    "files": [
        "db-error-code-catalog.md",
        "gateway-model-catalog.md",
        "release-history.md",
        "db-tuning.md",
        "hybrid-retrieval.md",
        "db-errors-general.md",
        "release-process.md",
        "gateway-overview.md",
        "index-basics.md",
        "retrieval-overview.md",
    ],
}

NEW_FORMAT_SET = {
    "name": "RAG 评估集 · 新格式语料",
    "files": [
        "gateway-capacity.xlsx",
        "release-briefing.pptx",
        "incident-tickets.csv",
        "ops-runbook.html",
    ],
}

CONTENT_TYPES = {
    ".md": "text/markdown",
    ".csv": "text/csv",
    ".html": "text/html",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
}


class Failure(Exception):
    pass


def call(base_url: str, method: str, path: str, body: object | None = None, token: str | None = None) -> object:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    request = urllib.request.Request(f"{base_url}{path}", data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            raw = response.read().decode()
    except urllib.error.HTTPError as error:
        raise Failure(f"{method} {path} -> HTTP {error.code}: {error.read().decode(errors='replace')}") from error
    return json.loads(raw) if raw else {}


def upload(base_url: str, token: str, knowledge_base_id: int, path: Path) -> int:
    boundary = "----build-eval-kb-boundary"
    content = path.read_bytes()
    content_type = CONTENT_TYPES.get(path.suffix.lower(), "application/octet-stream")
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    request = urllib.request.Request(
        f"{base_url}/api/v1/knowledge-bases/{knowledge_base_id}/documents/upload",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return int(json.loads(response.read().decode())["data"]["id"])
    except urllib.error.HTTPError as error:
        raise Failure(f"上传 {path.name} -> HTTP {error.code}: {error.read().decode(errors='replace')}") from error


def wait_until_processed(base_url: str, token: str, knowledge_base_id: int, documents: dict[str, int], timeout: int) -> None:
    """等所有文档解析完成。上传即返回，解析是异步的 —— 不等完就评估会检索到空索引。"""
    pending = dict(documents)
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        for name, document_id in list(pending.items()):
            status = call(
                base_url, "GET",
                f"/api/v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}/processing-status",
                token=token,
            )["data"]["status"]
            if status == "COMPLETED":
                print(f"    [完成] {name}")
                pending.pop(name)
            elif status in {"FAILED", "DISABLED"}:
                raise Failure(f"{name} 解析失败：{status}")
        if pending:
            time.sleep(2)
    if pending:
        raise Failure(f"等待解析超时，仍未完成：{list(pending)}")


def login_or_register(base_url: str) -> tuple[str, int]:
    try:
        data = call(base_url, "POST", "/api/v1/users/login", {"email": EVAL_EMAIL, "password": EVAL_PASSWORD})["data"]
        print(f"已登录评估账号：{EVAL_EMAIL}")
    except Failure:
        call(base_url, "POST", "/api/v1/users", {
            "username": "rag-eval", "email": EVAL_EMAIL, "password": EVAL_PASSWORD,
        })
        print(f"已注册评估账号：{EVAL_EMAIL}")
        data = call(base_url, "POST", "/api/v1/users/login", {"email": EVAL_EMAIL, "password": EVAL_PASSWORD})["data"]
    return data["accessToken"], int(data["user"]["id"])


def ensure_workspace(base_url: str, token: str, user_id: int) -> int:
    workspaces = call(base_url, "GET", "/api/v1/workspaces", token=token)["data"]
    for workspace in workspaces:
        if workspace["name"] == EVAL_WORKSPACE:
            return int(workspace["id"])
    # 注册不会自动建默认工作区，所以这里必须自己建一个
    created = call(base_url, "POST", "/api/v1/workspaces",
                   {"name": EVAL_WORKSPACE, "ownerUserId": user_id}, token=token)["data"]
    print(f"已创建工作区 {created['id']}（{EVAL_WORKSPACE}）")
    return int(created["id"])


def ensure_knowledge_base(base_url: str, token: str, workspace_id: int, name: str) -> tuple[int, set[str]]:
    """按名字复用；返回 (知识库 id, 库里已有的文件名)。"""
    existing = call(base_url, "GET", f"/api/v1/knowledge-bases?workspaceId={workspace_id}", token=token)["data"]
    for item in existing:
        if item["name"] != name:
            continue
        documents = call(
            base_url, "GET",
            f"/api/v1/workspaces/{workspace_id}/documents?knowledgeBaseId={item['id']}",
            token=token,
        )["data"]
        names = {str(document["originalFilename"]) for document in documents}
        print(f"复用知识库 {item['id']}（{name}），已有 {len(names)} 篇文档")
        return int(item["id"]), names

    created = call(base_url, "POST", "/api/v1/knowledge-bases",
                   {"workspaceId": workspace_id, "name": name, "description": "RAG 评估语料，供 evaluate_retrieval.py 使用"},
                   token=token)["data"]
    print(f"已创建知识库 {created['id']}（{name}）")
    return int(created["id"]), set()


def load_set(base_url: str, token: str, workspace_id: int, spec: dict, timeout: int) -> int:
    print(f"\n=== {spec['name']} ===")
    knowledge_base_id, present = ensure_knowledge_base(base_url, token, workspace_id, spec["name"])
    missing = [name for name in spec["files"] if name not in present]

    uploaded: dict[str, int] = {}
    for name in missing:
        path = CORPUS / name
        if not path.exists():
            raise Failure(f"语料文件不存在：{path}（先跑 docs/rag-eval/build_new_format_corpus.py）")
        uploaded[name] = upload(base_url, token, knowledge_base_id, path)
        print(f"    已上传 {name}（{path.stat().st_size} 字节）")

    if missing:
        wait_until_processed(base_url, token, knowledge_base_id, uploaded, timeout)
    else:
        print("    语料已齐全，无需上传")
    return knowledge_base_id


def main() -> int:
    parser = argparse.ArgumentParser(description="构建 RAG 评估知识库")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=600, help="等待解析完成的秒数")
    arguments = parser.parse_args()

    if not CORPUS.exists():
        raise Failure(f"语料目录不存在：{CORPUS}")

    token, user_id = login_or_register(arguments.base_url)
    workspace_id = ensure_workspace(arguments.base_url, token, user_id)

    result = {
        "workspace_id": workspace_id,
        "text": load_set(arguments.base_url, token, workspace_id, TEXT_SET, arguments.timeout),
        "new_formats": load_set(arguments.base_url, token, workspace_id, NEW_FORMAT_SET, arguments.timeout),
    }

    print("\n评估知识库就绪：")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("\n接着在 ai-service 容器里跑评估：")
    print(f"  -e AI_SERVICE_EVAL_KB_ID={result['text']}          # questions.json（28 题）")
    print(f"  -e AI_SERVICE_EVAL_KB_ID={result['new_formats']}   # questions-new-formats.json（13 题）")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Failure as error:
        print(f"\n构建失败：{error}", file=sys.stderr)
        sys.exit(1)
