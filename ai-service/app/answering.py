import json
from urllib.request import Request, urlopen

from app.indexing import retrieve_chunks
from app.settings import settings


def answer_question(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None = None,
    mode: str | None = None,
) -> dict[str, object]:
    sources = retrieve_chunks(knowledge_base_id, question, 5, mode)
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
    mode: str | None = None,
):
    sources = retrieve_chunks(knowledge_base_id, question, 5, mode)
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
    # 只有真的看到 [DONE] 才算正常结束。EOF 也可能意味着上游截断（网络断开、
    # 代理超时），此时把已收到的半截内容标成 complete，等于把失败伪装成成功 ——
    # 前端不会提示重试，用户拿到的是被悄悄截断的答案。
    completed = False
    try:
        with urlopen(request, timeout=90) as response:
            for raw_line in response:
                line = raw_line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    completed = True
                    break
                try:
                    frame = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                if isinstance(frame, dict) and frame.get("error"):
                    yield {"event": "error", "data": {"message": f"模型服务返回错误：{_error_message(frame['error'])}"}}
                    return
                try:
                    delta = frame["choices"][0].get("delta", {}).get("content", "")
                except (KeyError, IndexError, TypeError):
                    continue
                if delta:
                    answer_parts.append(delta)
                    yield {"event": "delta", "data": {"content": delta}}
    except Exception as exception:  # noqa: BLE001 - 读取中断也要变成事件流里的 error，而不是断连接
        yield {"event": "error", "data": {"message": f"模型流式调用失败：{exception}"}}
        return
    if not completed:
        yield {"event": "error", "data": {"message": "模型输出流未正常结束，回答可能不完整，请重试"}}
        return
    yield {"event": "complete", "data": {"answer": "".join(answer_parts), "sources": sources}}


def _error_message(error: object) -> str:
    if isinstance(error, dict):
        return str(error.get("message") or error.get("type") or "unknown error")
    return str(error)
