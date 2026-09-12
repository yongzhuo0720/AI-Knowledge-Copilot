import json
from urllib.request import Request, urlopen

from app.answering import answer_question
from app.indexing import retrieve_chunks
from app.settings import settings

TOOLS = [{
    "type": "function",
    "function": {
        "name": "knowledge_search",
        "description": "检索当前知识库中与问题最相关的文档片段。回答企业知识问题前必须先调用此工具。",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "要检索的问题或关键词"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 8, "default": 5},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
    },
}]


def run_knowledge_agent(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    if not settings.chat_api_key:
        fallback = answer_question(knowledge_base_id, question, history)
        return {
            "answer": fallback["answer"],
            "sources": fallback["sources"],
            "steps": [{"tool": "knowledge_search", "query": question, "result_count": len(fallback["sources"])}],
        }

    messages: list[dict[str, object]] = [{
        "role": "system",
        "content": "你是企业知识库 Agent。回答问题前必须调用 knowledge_search 工具，只依据工具返回的资料回答；资料不足时明确说明，不要编造。使用 [1]、[2] 标注引用。",
    }]
    messages.extend({"role": item["role"].lower(), "content": item["content"]} for item in (history or []))
    messages.append({"role": "user", "content": question})
    sources: list[dict[str, object]] = []
    steps: list[dict[str, object]] = []

    for _ in range(3):
        response = _chat_completion(messages, TOOLS)
        assistant_message = response["choices"][0]["message"]
        tool_calls = assistant_message.get("tool_calls") or []
        if not tool_calls:
            return {"answer": assistant_message.get("content") or "Agent 未生成回答。", "sources": sources, "steps": steps}
        messages.append(assistant_message)
        for tool_call in tool_calls:
            function = tool_call.get("function", {})
            if function.get("name") != "knowledge_search":
                result: list[dict[str, object]] = []
                query = question
                limit = 5
            else:
                try:
                    arguments = json.loads(function.get("arguments") or "{}")
                except json.JSONDecodeError:
                    arguments = {}
                query = str(arguments.get("query") or question).strip()[:1000]
                limit = _safe_limit(arguments.get("limit", 5))
                result = retrieve_chunks(knowledge_base_id, query, limit)
            sources.extend(item for item in result if item not in sources)
            steps.append({"tool": function.get("name", "unknown"), "query": query, "result_count": len(result)})
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.get("id", "knowledge-search"),
                "name": function.get("name", "knowledge_search"),
                "content": json.dumps(result, ensure_ascii=False),
            })
    return {"answer": "Agent 达到最大工具调用次数，请缩小问题范围后重试。", "sources": sources, "steps": steps}


def _chat_completion(messages: list[dict[str, object]], tools: list[dict[str, object]] | None) -> dict[str, object]:
    payload: dict[str, object] = {
        "model": settings.chat_model,
        "messages": messages,
        "temperature": 0.2,
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    request = Request(
        f"{settings.chat_base_url.rstrip('/')}/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": f"Bearer {settings.chat_api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=90) as response:
        return json.load(response)


def _safe_limit(value: object) -> int:
    try:
        return min(max(int(value), 1), 8)
    except (TypeError, ValueError):
        return 5
