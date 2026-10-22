"""Agent 工具集的单元测试。

这里不连模型，但**要**验证工具的行为契约：参数怎么收敛、返回的 status 长什么样、
失败时会不会抛异常、什么时候返回空、什么时候拒绝对知识库外的文档动手。
"""

import json

import pytest

from app import agent_tools
from app.settings import settings


@pytest.fixture
def context() -> agent_tools.AgentToolContext:
    return agent_tools.AgentToolContext(knowledge_base_id=7)


MARKDOWN_DOC = """# 发布流程

先跑测试，再打标签。

## 回滚

用上一个镜像重新部署。

## 加急

需要审批后跳过灰度。
"""


# --------------------------------------------------------------------------- #
# 工具清单本身
# --------------------------------------------------------------------------- #


def test_every_declared_tool_has_a_handler() -> None:
    """说明书和实现必须一一对应。

    只加 ``TOOLS`` 里的描述、忘了加 ``_HANDLERS``，模型会兴冲冲地调用一个不存在的工具，
    然后收到「未知工具」—— 从现象上看是「模型乱调工具」，实际是服务端漏注册。
    """
    declared = {spec["function"]["name"] for spec in agent_tools.TOOLS}
    assert declared == set(agent_tools._HANDLERS)


def test_tool_schemas_are_well_formed() -> None:
    for spec in agent_tools.TOOLS:
        assert spec["type"] == "function"
        function = spec["function"]
        assert function["name"]
        assert function["parameters"]["type"] == "object"
        # description 是模型选工具的唯一依据，不能省
        assert len(function["description"]) > 20
        assert "additionalProperties" in function["parameters"]


def test_schema_bounds_match_the_clamping_logic() -> None:
    """Schema 里写的区间和实现里收敛的区间必须一致，否则模型以为自己传对了。"""
    search = next(
        spec["function"]["parameters"]["properties"]["limit"]
        for spec in agent_tools.TOOLS
        if spec["function"]["name"] == "knowledge_search"
    )
    assert (search["minimum"], search["maximum"], search["default"]) == (
        agent_tools.SEARCH_MIN, agent_tools.SEARCH_MAX, agent_tools.SEARCH_DEFAULT
    )


# --------------------------------------------------------------------------- #
# 参数收敛
# --------------------------------------------------------------------------- #


def test_clamp_limit_falls_back_to_default_on_garbage() -> None:
    assert agent_tools.clamp_limit("not-a-number", 1, 8, 5) == 5
    assert agent_tools.clamp_limit(None, 1, 8, 5) == 5
    assert agent_tools.clamp_limit({"nested": 1}, 1, 8, 5) == 5


def test_clamp_limit_enforces_both_ends() -> None:
    """Schema 的 minimum/maximum 只是给模型的提示，不是强制校验 —— 实现必须自己兜住。"""
    assert agent_tools.clamp_limit(0, 1, 8, 5) == 1
    assert agent_tools.clamp_limit(-20, 1, 8, 5) == 1
    assert agent_tools.clamp_limit(99, 1, 8, 5) == 8


def test_knowledge_search_clamps_out_of_range_limit(context, monkeypatch) -> None:
    seen: list[int] = []
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda knowledge_base_id, query, limit, document=None, **kwargs: (
        seen.append(limit) or []
    ))

    agent_tools.knowledge_search(context, {"query": "发布流程", "limit": 999})

    assert seen == [agent_tools.SEARCH_MAX]


# --------------------------------------------------------------------------- #
# knowledge_search
# --------------------------------------------------------------------------- #


def test_knowledge_search_returns_hits_with_count(context, monkeypatch) -> None:
    hits = [{"document_object_key": "kb/7/guide.md", "content": "发布流程内容", "score": 0.91}]
    monkeypatch.setattr(
        agent_tools, "retrieve_chunks",
        lambda knowledge_base_id, query, limit, document=None, **kwargs: hits,
    )

    status = agent_tools.knowledge_search(context, {"query": "发布流程"})

    assert status.status == "ok"
    assert status.payload == {"results": hits}
    assert status.count == 1


def test_knowledge_search_disables_query_rewrite(context, monkeypatch) -> None:
    """Agent 路径必须显式关掉查询改写。

    这条断言守的是一个成本决定：这里的 query 是模型自己写的、它还会跨轮换措辞，
    工具内部再改写等于每轮多打一次模型。一旦有人把 ``rewrite=False`` 去掉，
    这条会红。
    """
    seen: list[object] = []
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: (
        seen.append(kwargs.get("rewrite")) or []
    ))
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {})

    agent_tools.knowledge_search(context, {"query": "发布流程"})

    assert seen == [False]


def test_knowledge_search_passes_document_filter_through(context, monkeypatch) -> None:
    seen: list[object] = []
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda knowledge_base_id, query, limit, document=None, **kwargs: (
        seen.append((knowledge_base_id, document)) or []
    ))
    # 返回空会触发「过滤条件是否有效」的核对，这里让文档存在，走 empty 分支
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/7/aaa/guide.md": 2})

    agent_tools.knowledge_search(context, {"query": "回滚", "document": "kb/7/aaa/guide.md"})

    assert seen == [(7, "kb/7/aaa/guide.md")]


def test_knowledge_search_rejects_blank_query(context, monkeypatch) -> None:
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: pytest.fail("不该执行检索"))

    status = agent_tools.knowledge_search(context, {"query": "   "})

    assert status.status == "error"
    assert status.count == 0


def test_knowledge_search_reports_empty_as_empty_not_error(context, monkeypatch) -> None:
    """「没查到」和「工具坏了」对模型是两种完全不同的处境，必须能区分。"""
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [])

    status = agent_tools.knowledge_search(context, {"query": "不存在的内容"})

    assert status.status == "empty"
    assert "换一组关键词" in status.payload["message"]


def test_knowledge_search_calls_out_an_invalid_document_filter(context, monkeypatch) -> None:
    """过滤条件指了一篇不存在的文档 → 必须明说「参数错了」，不能静默返回空。

    实测里模型会把**文件名**当成 object_key 传（它看到清单里显示的名字就是文件名），
    这时候静默返回空会让它连猜两轮，把工具预算烧光。所以要把正确取值摆到它面前。
    """
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [])
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {
        "kb/7/aaaa1111-uuid/guide.md": 3
    })

    status = agent_tools.knowledge_search(context, {"query": "回滚", "document": "guide.md"})

    assert status.status == "not_found"
    assert "kb/7/aaaa1111-uuid/guide.md" in status.payload


def test_knowledge_search_keeps_empty_separate_from_an_invalid_filter(context, monkeypatch) -> None:
    """文档确实存在、只是这一问没匹配上 → 这是 empty，不是参数错误。"""
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [])
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {
        "kb/7/aaaa1111-uuid/guide.md": 3
    })

    status = agent_tools.knowledge_search(
        context, {"query": "完全不相干的内容", "document": "kb/7/aaaa1111-uuid/guide.md"}
    )

    assert status.status == "empty"


def test_knowledge_search_skips_the_inventory_check_when_it_has_hits(context, monkeypatch) -> None:
    """清单校验只在失败路径上跑，正常检索不该为此多花一次全库扫描。"""
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [
        {"document_object_key": "kb/7/a.md", "content": "内容", "score": 0.5}
    ])
    monkeypatch.setattr(
        agent_tools, "document_inventory",
        lambda knowledge_base_id: pytest.fail("正常路径不该查文档清单"),
    )

    status = agent_tools.knowledge_search(context, {"query": "内容", "document": "kb/7/a.md"})

    assert status.status == "ok"


# --------------------------------------------------------------------------- #
# document_outline
# --------------------------------------------------------------------------- #


def _stub_document(monkeypatch, content: bytes, key: str = "kb/7/aaa/guide.md") -> None:
    """把「文档在库里」和「文档内容」两个外部依赖都换掉。"""
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {key: 4})
    monkeypatch.setattr(agent_tools, "download_object", lambda object_key: content)


def test_document_outline_lists_sections_in_document_order(context, monkeypatch) -> None:
    _stub_document(monkeypatch, MARKDOWN_DOC.encode())

    status = agent_tools.document_outline(context, {"document": "kb/7/aaa/guide.md"})

    assert status.status == "ok"
    assert status.payload["name"] == "guide.md"
    assert [section["heading"] for section in status.payload["sections"]] == [
        "发布流程",
        "发布流程 > 回滚",
        "发布流程 > 加急",
    ]
    assert status.payload["truncated"] is False
    assert status.count == 3


def test_document_outline_respects_limit(context, monkeypatch) -> None:
    _stub_document(monkeypatch, MARKDOWN_DOC.encode())

    status = agent_tools.document_outline(context, {"document": "kb/7/aaa/guide.md", "limit": 1})

    assert [section["heading"] for section in status.payload["sections"]] == ["发布流程"]
    assert status.payload["truncated"] is True
    assert status.count == 3, "count 报的是实际小节总数，不是被截断后的条数"


def test_document_outline_refuses_document_outside_the_knowledge_base(context, monkeypatch) -> None:
    """object_key 是共享存储里的路径，模型可以编造出别的知识库的 key。

    这是本模块最重要的一条安全约束：工具只能碰当前知识库的文档。
    """
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/7/aaa/guide.md": 4})
    monkeypatch.setattr(
        agent_tools, "download_object",
        lambda object_key: pytest.fail("越权文档不该被读取"),
    )

    status = agent_tools.document_outline(context, {"document": "kb/9/bbb/secret.md"})

    assert status.status == "not_found"
    assert "list_documents" in status.payload


def test_document_outline_falls_back_to_head_when_document_has_no_headings(context, monkeypatch) -> None:
    _stub_document(monkeypatch, "第一段说明。\n\n第二段说明。\n".encode(), key="kb/7/aaa/notes.txt")

    status = agent_tools.document_outline(context, {"document": "kb/7/aaa/notes.txt"})

    assert status.status == "ok"
    assert status.payload["sections"] == []
    assert "第一段说明。" in status.payload["head"]


def test_document_outline_names_content_before_the_first_heading(context, monkeypatch) -> None:
    """标题之前的那段正文不属于任何小节，但不能在目录里凭空消失。"""
    _stub_document(monkeypatch, "引言一段。\n\n# 发布流程\n\n正文。\n".encode())

    status = agent_tools.document_outline(context, {"document": "kb/7/aaa/guide.md"})

    assert [section["heading"] for section in status.payload["sections"]] == [
        agent_tools._NO_HEADING,
        "发布流程",
    ]


def test_document_outline_reports_error_when_download_fails(context, monkeypatch) -> None:
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/7/aaa/guide.md": 1})
    monkeypatch.setattr(
        agent_tools, "download_object",
        lambda object_key: (_ for _ in ()).throw(OSError("connection reset")),
    )

    status = agent_tools.document_outline(context, {"document": "kb/7/aaa/guide.md"})

    assert status.status == "error"
    assert "connection reset" in status.payload


def test_document_outline_requires_document_argument(context) -> None:
    status = agent_tools.document_outline(context, {})
    assert status.status == "error"


# --------------------------------------------------------------------------- #
# list_documents
# --------------------------------------------------------------------------- #


def test_list_documents_reports_previews(context, monkeypatch) -> None:
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {
        "kb/7/aaa/guide.md": 3,
        "kb/7/bbb/catalog.md": 2,
    })
    monkeypatch.setattr(agent_tools, "document_preview_chunks", lambda knowledge_base_id, document, limit: [
        "发布流程 > 回滚\n\n正文内容",
        "发布流程 > 加急\n\n正文内容",
    ])

    status = agent_tools.list_documents(context, {})

    assert status.status == "ok"
    assert status.payload["document_count"] == 2
    assert status.count == 2
    first = status.payload["documents"][0]
    # document 必须是 object_key 原值，模型才有东西可以原样回传给其它工具
    assert first["document"] == "kb/7/bbb/catalog.md"
    assert first["name"] == "catalog.md"
    assert first["chunks"] == 2
    assert first["preview"] == ["发布流程 > 回滚", "发布流程 > 加急"]


def test_list_documents_disambiguates_duplicate_filenames(context, monkeypatch) -> None:
    """同一个知识库里可以传两个同名文件，清单里两行长得分毫不差，模型就只能瞎猜。"""
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {
        "kb/7/aaaa1111-uuid/guide.md": 3,
        "kb/7/bbbb2222-uuid/guide.md": 2,
    })
    monkeypatch.setattr(agent_tools, "document_preview_chunks", lambda *args: [])

    status = agent_tools.list_documents(context, {})

    names = [document["name"] for document in status.payload["documents"]]
    assert names == ["guide.md（aaaa1111）", "guide.md（bbbb2222）"]
    assert len(set(names)) == 2


def test_list_documents_reports_empty_knowledge_base(context, monkeypatch) -> None:
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {})

    status = agent_tools.list_documents(context, {})

    assert status.status == "empty"
    assert status.payload["document_count"] == 0
    assert status.count == 0


def test_list_documents_respects_preview_setting(context, monkeypatch) -> None:
    seen: list[int] = []
    monkeypatch.setattr(agent_tools, "document_inventory", lambda knowledge_base_id: {"kb/7/a.md": 1})
    monkeypatch.setattr(
        agent_tools, "document_preview_chunks",
        lambda knowledge_base_id, document, limit: (seen.append(limit) or []),
    )
    monkeypatch.setattr(settings, "agent_tool_preview_chunks", 1)

    agent_tools.list_documents(context, {})

    assert seen == [1]


# --------------------------------------------------------------------------- #
# 分发
# --------------------------------------------------------------------------- #


def test_run_tool_returns_error_instead_of_raising(context, monkeypatch) -> None:
    """Agent 循环里抛异常会触发整体降级，一次工具故障不该放大成整轮问答失败。"""
    def boom(_context, _arguments):
        raise RuntimeError("boom")

    monkeypatch.setitem(agent_tools._HANDLERS, "list_documents", boom)

    status, content = agent_tools.run_tool(context, "list_documents", {})

    assert status.status == "error"
    # 回灌给模型的是正常文本，不是异常
    assert json.loads(content)["status"] == "error"


def test_run_tool_reports_unknown_tool(context) -> None:
    status, content = agent_tools.run_tool(context, "drop_database", {})

    assert status.status == "error"
    assert "drop_database" in status.payload
    assert all(name in status.payload for name in agent_tools._HANDLERS), "错误提示里要给出可用工具"
    assert json.loads(content)["status"] == "error"


def test_run_tool_wraps_payload_with_status_for_the_model(context, monkeypatch) -> None:
    """回灌文本必须带 status：只回一个裸数组时，「没查到」和「工具坏了」长得一模一样。"""
    monkeypatch.setattr(agent_tools, "retrieve_chunks", lambda *args, **kwargs: [
        {"document_object_key": "kb/7/a.md", "content": "内容", "score": 0.5}
    ])

    _, content = agent_tools.run_tool(context, "knowledge_search", {"query": "内容"})

    parsed = json.loads(content)
    assert parsed["status"] == "ok"
    assert parsed["data"]["results"][0]["document_object_key"] == "kb/7/a.md"


# --------------------------------------------------------------------------- #
# 执行轨迹文案
# --------------------------------------------------------------------------- #


def test_describe_call_is_human_readable() -> None:
    assert agent_tools.describe_call("knowledge_search", {"query": "发布流程"}) == "检索「发布流程」，取前 5 片"
    assert agent_tools.describe_call(
        "knowledge_search", {"query": "回滚", "limit": 2, "document": "kb/7/aaa/guide.md"}
    ) == "检索「回滚」，范围：guide.md，取前 2 片"
    assert agent_tools.describe_call("list_documents", {}) == "列出知识库中的全部文档"
    assert agent_tools.describe_call("document_outline", {"document": "kb/7/aaa/guide.md"}) == "查看《guide.md》的目录"


def test_describe_call_is_bounded_to_the_query_column() -> None:
    """agent_step.query 是 VARCHAR(1000)，超长会让整条消息的落库 INSERT 直接报错。"""
    description = agent_tools.describe_call("knowledge_search", {"query": "长" * 3000})
    assert len(description) <= 1000


def test_describe_call_handles_unknown_tool_and_bad_arguments() -> None:
    assert agent_tools.describe_call("mystery", {}) == "调用工具 mystery"
    assert agent_tools.describe_call("knowledge_search", None) == "检索「（未给出关键词）」，取前 5 片"
