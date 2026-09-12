from app import agent


def test_agent_falls_back_to_retrieval_answer_without_chat_key(monkeypatch) -> None:
    monkeypatch.setattr(agent.settings, "chat_api_key", "")
    monkeypatch.setattr(agent, "answer_question", lambda knowledge_base_id, question, history: {
        "answer": "检索到的回答",
        "sources": [{"document_object_key": "kb/1/guide.txt", "content": "资料", "score": 0.9}],
    })

    result = agent.run_knowledge_agent(1, "如何发布？", [])

    assert result["answer"] == "检索到的回答"
    assert result["steps"] == [{"tool": "knowledge_search", "query": "如何发布？", "result_count": 1}]


def test_agent_clamps_invalid_tool_limit() -> None:
    assert agent._safe_limit("not-a-number") == 5
    assert agent._safe_limit(0) == 1
    assert agent._safe_limit(99) == 8


def test_agent_executes_tool_call_and_returns_sources(monkeypatch) -> None:
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    calls = iter([
        {"choices": [{"message": {
            "role": "assistant",
            "content": None,
            "tool_calls": [{
                "id": "call-1",
                "function": {"name": "knowledge_search", "arguments": '{"query":"发布流程","limit":2}'},
            }],
        }}]},
        {"choices": [{"message": {"role": "assistant", "content": "发布流程见来源。[1]"}}]},
    ])
    requested_tools = []
    monkeypatch.setattr(agent, "_chat_completion", lambda messages, tools: (requested_tools.append(tools) or next(calls)))
    monkeypatch.setattr(agent, "retrieve_chunks", lambda knowledge_base_id, query, limit: [{
        "document_object_key": "kb/1/guide.md",
        "content": "发布流程内容",
        "score": 0.91,
    }])

    result = agent.run_knowledge_agent(1, "怎么发布？", [])

    assert result["answer"] == "发布流程见来源。[1]"
    assert result["steps"] == [{"tool": "knowledge_search", "query": "发布流程", "result_count": 1}]
    assert result["sources"][0]["document_object_key"] == "kb/1/guide.md"
    assert requested_tools == [agent.TOOLS, agent.TOOLS]
