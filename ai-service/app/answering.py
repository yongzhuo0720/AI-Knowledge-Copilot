import json
from urllib.request import Request, urlopen

from app.indexing import retrieve_chunks
from app.settings import settings


def answer_question(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    sources = retrieve_chunks(knowledge_base_id, question, 5)
    if not sources:
        return {"answer": "未在该知识库中找到可用资料。", "sources": []}
    context = "\n\n".join(f"[{index + 1}] {source['content']}" for index, source in enumerate(sources))
    if not settings.chat_api_key:
        return {"answer": "尚未配置聊天模型密钥，以下是检索到的资料。", "sources": sources}
    history_context = "\n".join(
        f"{item['role']}: {item['content']}" for item in (history or [])
    )
    prompt = (
        "只依据以下资料用中文回答问题；资料不足时明确说明。不要编造事实。"
        "在回答相关句末使用 [1]、[2] 等编号引用资料。\n\n"
        f"历史对话：\n{history_context or '无'}\n\n"
        f"资料：\n{context}\n\n问题：{question}"
    )
    request = Request(
        f"{settings.chat_base_url.rstrip('/')}/chat/completions",
        data=json.dumps({"model": settings.chat_model, "messages": [{"role": "user", "content": prompt}], "temperature": 0.2}).encode(),
        headers={"Authorization": f"Bearer {settings.chat_api_key}", "Content-Type": "application/json"}, method="POST",
    )
    with urlopen(request, timeout=60) as response:
        answer = json.load(response)["choices"][0]["message"]["content"]
    return {"answer": answer, "sources": sources}


def stream_answer_question(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None = None,
):
    sources = retrieve_chunks(knowledge_base_id, question, 5)
    yield {"event": "sources", "data": {"sources": sources}}
    if not sources:
        yield {"event": "complete", "data": {"answer": "未在该知识库中找到可用资料。", "sources": []}}
        return
    context = "\n\n".join(f"[{index + 1}] {source['content']}" for index, source in enumerate(sources))
    if not settings.chat_api_key:
        fallback = "尚未配置聊天模型密钥，以下是检索到的资料。"
        yield {"event": "delta", "data": {"content": fallback}}
        yield {"event": "complete", "data": {"answer": fallback, "sources": sources}}
        return
    history_context = "\n".join(
        f"{item['role']}: {item['content']}" for item in (history or [])
    )
    prompt = (
        "只依据以下资料用中文回答问题；资料不足时明确说明。不要编造事实。"
        "在回答相关句末使用 [1]、[2] 等编号引用资料。\n\n"
        f"历史对话：\n{history_context or '无'}\n\n"
        f"资料：\n{context}\n\n问题：{question}"
    )
    request = Request(
        f"{settings.chat_base_url.rstrip('/')}/chat/completions",
        data=json.dumps({
            "model": settings.chat_model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.2,
            "stream": True,
        }).encode(),
        headers={"Authorization": f"Bearer {settings.chat_api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    answer_parts: list[str] = []
    with urlopen(request, timeout=90) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            try:
                delta = json.loads(payload)["choices"][0].get("delta", {}).get("content", "")
            except (KeyError, IndexError, TypeError, json.JSONDecodeError):
                continue
            if delta:
                answer_parts.append(delta)
                yield {"event": "delta", "data": {"content": delta}}
    yield {"event": "complete", "data": {"answer": "".join(answer_parts), "sources": sources}}
