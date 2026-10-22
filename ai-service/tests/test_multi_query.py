"""多查询（Multi-Query）检索的单元测试。

这一层架在 ``hybrid_retrieve`` **之上**，所以测试要钉住的是它的三层契约：

1. **没有改写时行为与单查询完全一致** —— 开关关掉、或模型没给出任何改写时，
   它必须原样返回 ``hybrid_retrieve`` 的结果，不能多跑一次、也不能改变排序。
   这是「关掉开关就等于没这个功能」的保证。
2. **融合只看名次、不看分数** —— 被多条改写同时召回的片段要被抬到前面。
   这与混合检索里 RRF 的性质是同一个道理，只是作用在「不同说法」之间。
3. **用原问题重排** —— 改写负责「多召回」，相关性仍由用户真正问的那句话定义。

全部 monkeypatch 掉真实检索与模型调用，不连 Milvus、不碰网络。
"""

import pytest

from app import hybrid_search
from app import query_rewrite
from app.hybrid_search import multi_query_retrieve


@pytest.fixture(autouse=True)
def _no_rewrite_by_default(monkeypatch):
    """默认不真的去调模型：绝大多数用例显式传 rewrites。

    这样任何「意外调用模型」都会立刻变成测试失败，而不是悄悄多打一次 API。
    """
    monkeypatch.setattr(query_rewrite, "rewrite_query", lambda *args, **kwargs: [])


def _hit(object_key: str, content: str) -> dict[str, object]:
    return {"document_object_key": object_key, "content": content, "score": 0.5, "retriever": "vector"}


def _keys(hits: list[dict[str, object]]) -> list[str]:
    return [str(hit["document_object_key"]) for hit in hits]


# --------------------------------------------------------------------------- #
# 契约 1：没有改写时与单查询一致
# --------------------------------------------------------------------------- #

def test_falls_back_to_plain_hybrid_retrieve_when_there_are_no_rewrites(monkeypatch) -> None:
    calls: list[str] = []

    def fake_retrieve(knowledge_base_id, query, limit, mode=None, document=None):
        calls.append(query)
        return [_hit("kb/1/a.md", "A 的内容")]

    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", fake_retrieve)

    hits = multi_query_retrieve(1, "原问题", 3, "hybrid", rewrites=[])

    assert _keys(hits) == ["kb/1/a.md"]
    # 关键：只跑了一次。多跑一次就说明「关掉改写」并没有真的退化成单查询。
    assert calls == ["原问题"]


def test_asks_the_rewriter_when_no_rewrites_are_passed(monkeypatch) -> None:
    asked: list[str] = []
    monkeypatch.setattr(query_rewrite, "rewrite_query", lambda question, count=None: asked.append(question) or [])
    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", lambda *args, **kwargs: [_hit("kb/1/a.md", "A")])

    multi_query_retrieve(1, "原问题", 3)

    assert asked == ["原问题"]


def test_does_not_ask_the_rewriter_when_rewrites_are_given(monkeypatch) -> None:
    monkeypatch.setattr(
        query_rewrite, "rewrite_query",
        lambda *args, **kwargs: pytest.fail("显式给了改写就不该再请求模型"),
    )
    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", lambda *args, **kwargs: [_hit("kb/1/a.md", "A")])

    multi_query_retrieve(1, "原问题", 3, rewrites=["改写一"])


def test_ignores_blank_rewrites(monkeypatch) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        hybrid_search, "hybrid_retrieve",
        lambda knowledge_base_id, query, limit, mode=None, document=None: (calls.append(query), [_hit("kb/1/a.md", "A")])[1],
    )

    multi_query_retrieve(1, "原问题", 3, rewrites=["  ", ""])

    assert calls == ["原问题"], "空白改写应被丢掉，否则就是白跑一遍检索"


# --------------------------------------------------------------------------- #
# 契约 2：融合跨改写做
# --------------------------------------------------------------------------- #

def test_promotes_chunks_recalled_by_more_variants(monkeypatch) -> None:
    """被两条说法同时召回的 B 必须排到只被一条召回的前面。

    这正是多查询的收益来源：**「多种说法都指向它」是「它是答案」的强信号**。
    A 在原问题里排第 1、在改写里没出现；B 两边都出现但都排第 2 —— 融合后 B 胜出。
    """

    def fake_retrieve(knowledge_base_id, query, limit, mode=None, document=None):
        if query == "原问题":
            return [_hit("kb/1/a.md", "A 的内容"), _hit("kb/1/b.md", "B 的内容")]
        return [_hit("kb/1/b.md", "B 的内容"), _hit("kb/1/c.md", "C 的内容")]

    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", fake_retrieve)

    hits = multi_query_retrieve(1, "原问题", 3, "hybrid", rewrites=["换一种说法"])

    assert _keys(hits)[0] == "kb/1/b.md"


def test_keeps_original_query_ranking_when_only_one_variant_returns_hits(monkeypatch) -> None:
    def fake_retrieve(knowledge_base_id, query, limit, mode=None, document=None):
        if query == "原问题":
            return [_hit("kb/1/a.md", "A"), _hit("kb/1/b.md", "B")]
        return []

    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", fake_retrieve)

    hits = multi_query_retrieve(1, "原问题", 3, "hybrid", rewrites=["没有命中的说法"])

    assert _keys(hits) == ["kb/1/a.md", "kb/1/b.md"]


def test_returns_empty_when_every_variant_is_empty(monkeypatch) -> None:
    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", lambda *args, **kwargs: [])

    assert multi_query_retrieve(1, "原问题", 3, rewrites=["改写一", "改写二"]) == []


def test_reports_how_many_variants_recalled_each_chunk(monkeypatch) -> None:
    """``rewrite_hits`` 是多查询独有的证据：它让「这条为什么被抬上来」可解释。"""

    def fake_retrieve(knowledge_base_id, query, limit, mode=None, document=None):
        if query == "原问题":
            return [_hit("kb/1/a.md", "A"), _hit("kb/1/b.md", "B")]
        return [_hit("kb/1/b.md", "B"), _hit("kb/1/c.md", "C")]

    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", fake_retrieve)

    hits = multi_query_retrieve(1, "原问题", 3, "hybrid", rewrites=["改写一"])
    by_key = {str(hit["document_object_key"]): hit for hit in hits}

    assert by_key["kb/1/b.md"]["rewrite_hits"] == 2, "B 被原问题和改写都召回了"
    assert by_key["kb/1/a.md"]["rewrite_hits"] == 1
    # 跨改写合并之后「是向量还是关键词召回的」已经没有意义，统一标记
    assert all(hit["retriever"] == "multi-query" for hit in hits)


def test_deduplicates_the_same_chunk_across_variants(monkeypatch) -> None:
    """同一片段被多条改写召回时只能出现一次，否则引用面板会重复列同一段资料。"""
    monkeypatch.setattr(
        hybrid_search, "hybrid_retrieve",
        lambda *args, **kwargs: [_hit("kb/1/a.md", "同一段内容")],
    )

    hits = multi_query_retrieve(1, "原问题", 5, rewrites=["改写一", "改写二", "改写三"])

    assert len(hits) == 1
    assert hits[0]["rewrite_hits"] == 4, "原问题 + 3 条改写全都召回了它"


# --------------------------------------------------------------------------- #
# 契约 3：用原问题重排
# --------------------------------------------------------------------------- #

def test_reranks_with_the_original_question_not_a_rewrite(monkeypatch) -> None:
    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", lambda *args, **kwargs: [_hit("kb/1/a.md", "A")])
    seen: list[str] = []

    def fake_rerank(query, candidates, top_n):
        seen.append(query)
        return candidates[:top_n]

    monkeypatch.setattr(hybrid_search, "rerank", fake_rerank)

    multi_query_retrieve(1, "原始问题", 3, rewrites=["改写一", "改写二"])

    assert seen == ["原始问题"]


def test_skips_rerank_when_disabled(monkeypatch) -> None:
    monkeypatch.setattr(hybrid_search.settings, "rerank_enabled", False)
    monkeypatch.setattr(hybrid_search, "hybrid_retrieve", lambda *args, **kwargs: [_hit("kb/1/a.md", "A")])
    monkeypatch.setattr(hybrid_search, "rerank", lambda *args, **kwargs: pytest.fail("关掉重排就不该调用它"))

    hits = multi_query_retrieve(1, "原问题", 3, rewrites=["改写一"])

    assert _keys(hits) == ["kb/1/a.md"]


def test_respects_the_requested_limit(monkeypatch) -> None:
    monkeypatch.setattr(
        hybrid_search, "hybrid_retrieve",
        lambda *args, **kwargs: [_hit(f"kb/1/{index}.md", f"内容 {index}") for index in range(10)],
    )

    assert len(multi_query_retrieve(1, "原问题", 2, "hybrid", rewrites=["改写一"])) == 2
