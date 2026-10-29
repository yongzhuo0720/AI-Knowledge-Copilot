"""Agent 循环的单元测试。

不连模型、不连 Milvus：``_chat_completion`` 和工具侧的检索函数都被替换成假实现，
所以这里验证的是**循环控制逻辑**本身 —— 工具怎么分发、结果怎么回灌、什么时候降级。
"""

from app import agent, agent_tools


def _tool_call(call_id: str, name: str, arguments: str = "{}") -> dict:
    return {"id": call_id, "function": {"name": name, "arguments": arguments}}


def _assistant_with_tool_calls(*calls: dict) -> dict:
    return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": list(calls)}}]}


def _assistant_with_answer(content: str) -> dict:
    return {"choices": [{"message": {"role": "assistant", "content": content}}]}


def _stub_renderer(responses: list[dict], requested_tools: list | None = None):
    """按顺序吐出预置响应，并记录每轮实际传了哪些工具。"""
    pending = iter(responses)

    def fake_chat_completion(messages, tools):
        if requested_tools is not None:
            requested_tools.append(tools)
        return next(pending)

    return fake_chat_completion


# --------------------------------------------------------------------------- #
# 降级路径
# --------------------------------------------------------------------------- #


def test_agent_falls_back_to_retrieval_answer_without_chat_key(monkeypatch) -> None:
    monkeypatch.setattr(agent.settings, "chat_api_key", "")
    monkeypatch.setattr(agent, "answer_question", lambda knowledge_base_id, question, history: {
        "answer": "检索到的回答",
        "sources": [{"document_object_key": "kb/1/guide.txt", "content": "资料", "score": 0.9}],
    })

    result = agent.run_knowledge_agent(1, "如何发布？", [])

    assert result["answer"] == "检索到的回答"
    # 降级本身必须如实标注：伪造一条 knowledge_search 会让轨迹看起来像正常走完了工具链
    assert result["steps"] == [{
        "tool": "fallback",
        "query": "降级：未配置模型密钥，改为检索直答",
        "result_count": 1,
    }]


def test_agent_falls_back_when_tool_call_api_fails(monkeypatch) -> None:
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(
        agent, "_chat_completion",
        lambda messages, tools: (_ for _ in ()).throw(RuntimeError("temporary API failure")),
    )
    monkeypatch.setattr(agent, "answer_question", lambda knowledge_base_id, question, history: {
        "answer": "普通回答已返回",
        "sources": [{"document_object_key": "kb/1/guide.md", "content": "资料", "score": 0.9}],
    })

    result = agent.run_knowledge_agent(1, "如何发布？", [])

    assert result["answer"] == "普通回答已返回"
    assert result["steps"] == [{
        "tool": "fallback",
        "query": "降级：工具调用失败（RuntimeError），改为检索直答",
        "result_count": 1,
    }]


def test_fallback_keeps_the_real_trace_instead_of_faking_a_search(monkeypatch) -> None:
    """先成功列过文档，下一轮模型调用超时：已执行的步骤必须保留。

    曾经的实现会把整条轨迹替换成一条 knowledge_search —— 已经跑成功的工具、
    失败发生在哪一步全被抹掉，只剩下一个看起来「检索成功」的假象。
    """
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    responses = iter([_assistant_with_tool_calls(_tool_call("call-1", "list_documents"))])

    def fake_chat_completion(messages, tools):
        try:
            return next(responses)
        except StopIteration:
            raise TimeoutError("model request timed out")

    monkeypatch.setattr(agent, "_chat_completion", fake_chat_completion)
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/1/a.md": 2, "kb/1/b.md": 1})
    monkeypatch.setattr(agent_tools, "document_preview_chunks", lambda knowledge_base_id, document, limit: [])
    monkeypatch.setattr(agent, "answer_question", lambda knowledge_base_id, question, history: {
        "answer": "降级回答",
        "sources": [{"document_object_key": "kb/1/a.md", "content": "资料", "score": 0.9}],
    })

    result = agent.run_knowledge_agent(1, "知识库里有什么？", [])

    assert [step["tool"] for step in result["steps"]] == ["list_documents", "fallback"]
    assert result["steps"][0] == {
        "tool": "list_documents",
        "query": "列出知识库中的全部文档",
        "result_count": 2,
    }
    assert "TimeoutError" in result["steps"][1]["query"], "降级步骤要写清楚真实失败类型"
    assert result["answer"] == "降级回答"


# --------------------------------------------------------------------------- #
# 正常路径
# --------------------------------------------------------------------------- #


def test_agent_executes_tool_call_and_returns_sources(monkeypatch) -> None:
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    requested_tools: list = []
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer(
        [
            _assistant_with_tool_calls(_tool_call("call-1", "knowledge_search", '{"query":"发布流程","limit":2}')),
            _assistant_with_answer("发布流程见来源。[1]"),
        ],
        requested_tools,
    ))
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda knowledge_base_id, query, limit, document=None, **kwargs: [{
        "document_object_key": "kb/1/guide.md",
        "content": "发布流程内容",
        "score": 0.91,
    }])

    result = agent.run_knowledge_agent(1, "怎么发布？", [])

    assert result["answer"] == "发布流程见来源。[1]"
    assert result["steps"] == [{
        "tool": "knowledge_search",
        "query": "检索「发布流程」，取前 2 片",
        "result_count": 1,
    }]
    assert result["sources"][0]["document_object_key"] == "kb/1/guide.md"
    assert requested_tools == [agent.TOOLS, agent.TOOLS]


def test_agent_reuses_result_when_model_repeats_the_same_call(monkeypatch) -> None:
    """一轮里给出两个相同调用（模型确实会这么干）只该执行一次。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer([
        _assistant_with_tool_calls(
            _tool_call("call-1", "knowledge_search", '{"query":"发布流程","limit":5}'),
            # 参数顺序打乱：规范化后应与上面是同一条缓存键
            _tool_call("call-2", "knowledge_search", '{"limit":5,"query":"发布流程"}'),
        ),
        _assistant_with_answer("答案"),
    ]))
    executions: list[str] = []
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda knowledge_base_id, query, limit, document=None, **kwargs: (
        executions.append(query) or [{"document_object_key": "kb/1/a.md", "content": "内容", "score": 0.5}]
    ))

    result = agent.run_knowledge_agent(1, "怎么发布？", [])

    assert executions == ["发布流程"], "同一次问答内重复的调用不该执行第二遍"
    assert [step["result_count"] for step in result["steps"]] == [1, 1]
    assert len(result["sources"]) == 1, "来源去重后仍只有一条"


def test_agent_keeps_calls_with_different_arguments_apart(monkeypatch) -> None:
    """缓存键必须带参数，否则对不同文档的调用会互相串结果。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer([
        _assistant_with_tool_calls(
            _tool_call("call-1", "knowledge_search", '{"query":"流程","document":"kb/1/a.md"}'),
            _tool_call("call-2", "knowledge_search", '{"query":"流程","document":"kb/1/b.md"}'),
        ),
        _assistant_with_answer("答案"),
    ]))
    executions: list[object] = []
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda knowledge_base_id, query, limit, document=None, **kwargs: (
        executions.append(document) or []
    ))

    agent.run_knowledge_agent(1, "怎么发布？", [])

    assert executions == ["kb/1/a.md", "kb/1/b.md"]


def test_agent_does_not_count_navigation_tools_as_sources(monkeypatch) -> None:
    """文档清单 / 目录是导航信息，不能进 sources —— 否则前端会把它们列成引用来源。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer([
        _assistant_with_tool_calls(_tool_call("call-1", "list_documents")),
        _assistant_with_answer("这个知识库有 2 篇文档。"),
    ]))
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/1/a.md": 2})
    monkeypatch.setattr(agent_tools, "document_preview_chunks", lambda knowledge_base_id, document, limit: [])

    result = agent.run_knowledge_agent(1, "知识库里有哪些文档？", [])

    assert result["sources"] == []
    assert result["steps"] == [{
        "tool": "list_documents",
        "query": "列出知识库中的全部文档",
        "result_count": 1,
    }]


def test_agent_records_unknown_tool_without_falling_back(monkeypatch) -> None:
    """模型拼错工具名是很常见的失败，不该把整轮问答降级掉。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer([
        _assistant_with_tool_calls(_tool_call("call-1", "delete_everything")),
        _assistant_with_answer("我无法执行该操作，但可以帮你检索资料。"),
    ]))

    result = agent.run_knowledge_agent(1, "删库", [])

    assert result["answer"] == "我无法执行该操作，但可以帮你检索资料。"
    assert result["steps"] == [{"tool": "delete_everything", "query": "调用工具 delete_everything", "result_count": 0}]


def test_agent_survives_malformed_tool_arguments(monkeypatch) -> None:
    """参数不是合法 JSON（流式拼接被截断）时应退化成空参数，而不是抛异常。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent, "_chat_completion", _stub_renderer([
        _assistant_with_tool_calls(_tool_call("call-1", "knowledge_search", '{"query": "发布')),
        _assistant_with_answer("答案"),
    ]))

    result = agent.run_knowledge_agent(1, "怎么发布？", [])

    assert result["answer"] == "答案"
    assert result["steps"][0]["result_count"] == 0


def test_agent_answers_without_tools_when_the_budget_is_exhausted(monkeypatch) -> None:
    """轮数用尽时必须再问一次（这次不带工具），否则刚查回来的资料全被丢掉。

    实测里模型很爱探索（一次提问能连发四个工具调用），走到这个分支一点都不罕见；
    直接回一句「达到最大轮数」，用户就看到了一条内部错误，而它本来完全答得出来。
    """
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent.settings, "agent_max_rounds", 1)
    prompts: list[object] = []

    def fake_chat_completion(messages, tools):
        prompts.append(tools)
        if tools is None:
            return _assistant_with_answer("依据已查到的资料，电量低于 30% 必须回桩充电。[1]")
        return _assistant_with_tool_calls(_tool_call("call-1", "knowledge_search", '{"query":"电量"}'))

    monkeypatch.setattr(agent, "_chat_completion", fake_chat_completion)
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [
        {"document_object_key": "kb/1/a.md", "content": "电量低于 30% 必须回桩充电", "score": 0.9}
    ])

    result = agent.run_knowledge_agent(1, "电量低于多少要充电？", [])

    assert prompts == [agent.TOOLS, None], "收尾那一轮必须不带工具，模型才没有别的路可走"
    assert result["answer"] == "依据已查到的资料，电量低于 30% 必须回桩充电。[1]"
    assert len(result["sources"]) == 1


def test_agent_stops_at_max_rounds(monkeypatch) -> None:
    """模型一直要求调工具时必须有硬上限，否则一次提问能烧掉不可控的时间。"""
    monkeypatch.setattr(agent.settings, "chat_api_key", "test-key")
    monkeypatch.setattr(agent.settings, "agent_max_rounds", 2)
    monkeypatch.setattr(agent, "_chat_completion", lambda messages, tools: (
        _assistant_with_tool_calls(_tool_call(f"call-{len(messages)}", "list_documents"))
    ))
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/1/a.md": 1})
    monkeypatch.setattr(agent_tools, "document_preview_chunks", lambda knowledge_base_id, document, limit: [])

    result = agent.run_knowledge_agent(1, "有哪些文档？", [])

    assert result["answer"] == "Agent 达到最大工具调用次数，请缩小问题范围后重试。"
    assert len(result["steps"]) == 2, "上限为 2 轮，就应该只留下 2 次工具调用记录"
