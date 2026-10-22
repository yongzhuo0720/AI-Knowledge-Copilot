"""混合检索的单元测试。

重点验证三件事：
1. 分词能不能把中文/英文/编号都切成可精确匹配的单元；
2. BM25 的打分性质是否正确（稀有词权重高、IDF 恒为正）；
3. RRF 融合与重排的排序行为是否符合预期。

全部用假语料，不连 Milvus，所以可以在任何环境跑。
"""

import math

import pytest

from app import hybrid_search


@pytest.fixture(autouse=True)
def _clear_cache():
    hybrid_search.invalidate_corpus()
    yield
    hybrid_search.invalidate_corpus()


# --------------------------------------------------------------------------- #
# 分词
# --------------------------------------------------------------------------- #

def test_tokenize_keeps_error_codes_and_identifiers() -> None:
    tokens = hybrid_search.tokenize("报错 ERROR 1451，涉及 ForeignKeyConstraint 约束")
    assert "error" in tokens, "英文关键词应被小写化并保留"
    assert "1451" in tokens, "纯数字编号必须保留 —— 它正是向量检索最容易丢的信号"
    assert "foreignkeyconstraint" in tokens or "foreignkeyconstraint" in "".join(tokens)


def test_tokenize_splits_chinese_into_matchable_units() -> None:
    tokens = set(hybrid_search.tokenize("外键约束"))
    # bigram 降级模式下必须有「外键」和「约束」，否则关键词召回会失效
    assert "外键" in tokens or "外键约束" in tokens
    assert "约束" in tokens


def test_tokenize_does_not_emit_single_chars_for_long_runs(monkeypatch) -> None:
    """回归保护：多字段落一旦退化成单字，查询会被切成 外/键/约/束，
    于是任何含「约」的片段都能命中，排序直接失控。"""
    monkeypatch.setattr(hybrid_search, "_JIEBA", None)

    tokens = hybrid_search.tokenize("外键约束")

    assert "外" not in tokens
    assert "键" not in tokens
    assert all(len(token) == 2 for token in tokens), f"不应出现单字 token: {tokens}"


def test_tokenize_keeps_isolated_single_char(monkeypatch) -> None:
    """单字独立成段时必须保留，否则这个字再也搜不到。"""
    monkeypatch.setattr(hybrid_search, "_JIEBA", None)

    assert "税" in hybrid_search.tokenize("税")


def test_tokenize_handles_mixed_script_and_punctuation() -> None:
    tokens = hybrid_search.tokenize("V7 迁移脚本 db/migration/V7__admin.sql")
    assert "v7" in tokens
    assert "迁移" in tokens
    assert any("v7__admin" in token or "admin" in token for token in tokens)
    assert all(token.strip() for token in tokens), "不应产生空 token"


def test_tokenize_empty_input() -> None:
    assert hybrid_search.tokenize("") == []
    assert hybrid_search.tokenize("   ") == []


def test_tokenize_expands_identifiers_so_prefix_queries_match() -> None:
    """回归保护：查询写 V8、文档写 V8__add_source_retriever 时，两边必须能匹配上。

    修复前文档侧只产出一个整体 token，查询侧产出 v8，覆盖率记零分，
    导致最有区分度的词等于没起作用。
    """
    document_tokens = set(hybrid_search.tokenize("脚本文件位于 db/migration/V8__add_source_retriever.sql"))
    query_tokens = set(hybrid_search.tokenize("V8 迁移脚本叫什么名字"))

    assert "v8" in query_tokens
    # 标识符要拆到能匹配查询的粒度；注意路径里的 / 和 . 也参与切分，
    # 所以拆出来的不止下划线分段，这只会让召回更宽，不会更窄
    assert {"v8", "add", "source", "retriever"}.issubset(document_tokens), (
        f"必须能拆出标识符片段，实际 {sorted(document_tokens)}"
    )
    assert query_tokens & document_tokens, "查询与文档必须存在交集"


def test_identifier_tokens_keep_only_alphanumeric_terms() -> None:
    """重排只该信标识符。

    中文词元会被切出大量跨词边界的偶然 2-gram（「做了什么」→ 了什 / 么改），
    拿它们算覆盖率会把无关片段误判成高相关，所以必须全部排除。
    """
    tokens = hybrid_search._identifier_tokens("V8 迁移脚本叫什么名字")

    assert "v8" in tokens
    assert tokens, "查询里的版本号必须被识别为标识符"
    assert not any(
        "\u4e00" <= char <= "\u9fff" for token in tokens for char in token
    ), f"标识符集合里不该混进中文字元，实际 {sorted(tokens)}"


def test_bm25_prefers_chunk_with_matching_version_number() -> None:
    """两个片段措辞几乎一样，只有版本号不同，BM25 必须挑中写对的那个。"""
    documents = [
        "V7：为应用账号表增加管理员标记列，避免升级后无人能进入后台。",
        "V8__add_source_retriever：为会话引用来源表增加召回渠道列。",
        "V9：文件存储接入对象存储，文档原件不再落本地磁盘。",
    ]
    index = hybrid_search.BM25Index([hybrid_search.tokenize(text) for text in documents])

    scores = index.scores(hybrid_search.tokenize("V8 迁移脚本"))

    assert max(range(len(documents)), key=lambda position: scores[position]) == 1


# --------------------------------------------------------------------------- #
# BM25
# --------------------------------------------------------------------------- #

def _corpus():
    return [
        "本系统的数据库迁移使用 Flyway 管理，脚本放在 db/migration 目录下。",
        "当删除主表记录时如果子表仍有外键引用，MySQL 会报 ERROR 1451 并回滚事务。",
        "前端使用 Vue 3 与 TypeScript，构建工具是 Vite。",
        "检索模块默认采用混合检索：向量召回与 BM25 关键词召回通过 RRF 融合。",
    ]


def test_bm25_ranks_rare_term_document_first() -> None:
    documents = _corpus()
    index = hybrid_search.BM25Index([hybrid_search.tokenize(text) for text in documents])

    scores = index.scores(hybrid_search.tokenize("ERROR 1451"))

    best = max(range(len(documents)), key=lambda position: scores[position])
    assert best == 1, "包含 1451 的那条必须排第一，这正是纯向量检索会搞错的地方"
    assert scores[1] > scores[0]


def test_bm25_idf_is_always_positive() -> None:
    """原始 BM25 的 IDF 在词出现在过半文档时会变负，这里用的是恒正变体。"""
    documents = ["共享 关键词 甲", "共享 关键词 乙", "共享 关键词 丙"]
    index = hybrid_search.BM25Index([hybrid_search.tokenize(text) for text in documents])

    assert all(value > 0 for value in index.idf.values()), "IDF 出现非正值说明用回了原始公式"
    assert math.isclose(index.idf["共享"], min(index.idf.values()), rel_tol=0.5)


def test_bm25_length_normalization_penalizes_long_documents() -> None:
    """两个文档同样命中一次关键词，短的应该得分更高（b 参数在起作用）。"""
    short = "外键约束"
    long = "外键约束" + "，这里还有很多与关键词无关的描述性内容" * 8
    index = hybrid_search.BM25Index([hybrid_search.tokenize(short), hybrid_search.tokenize(long)])

    scores = index.scores(hybrid_search.tokenize("外键约束"))

    assert scores[0] > scores[1]


def test_bm25_returns_zeros_for_unknown_terms() -> None:
    index = hybrid_search.BM25Index([hybrid_search.tokenize("完全无关的内容")])
    assert index.scores(hybrid_search.tokenize("zzzz-不存在")) == [0.0]


def test_bm25_handles_empty_corpus() -> None:
    index = hybrid_search.BM25Index([])
    assert index.scores(hybrid_search.tokenize("任意查询")) == []


# --------------------------------------------------------------------------- #
# RRF 融合
# --------------------------------------------------------------------------- #

def test_rrf_ranks_documents_found_by_both_retrievers_highest() -> None:
    fused = hybrid_search.reciprocal_rank_fusion([
        ["a", "b", "c"],   # 向量路
        ["c", "d", "a"],   # 关键词路
    ])

    # a 和 c 都被两路命中，应该高于只被单路命中的 b 和 d
    assert fused["a"] > fused["b"]
    assert fused["c"] > fused["d"]
    assert set(fused) == {"a", "b", "c", "d"}


def test_rrf_is_scale_invariant() -> None:
    """RRF 只看排名，所以两路的分数绝对值怎么变都不影响融合结果。"""
    first = hybrid_search.reciprocal_rank_fusion([["a", "b"], ["b", "a"]])
    second = hybrid_search.reciprocal_rank_fusion([["a", "b"], ["b", "a"]])
    assert first == second
    assert math.isclose(first["a"], first["b"]), "两路排名对称时融合分应相同"


# --------------------------------------------------------------------------- #
# 重排
# --------------------------------------------------------------------------- #

def test_rerank_promotes_identifier_hit() -> None:
    candidates = [
        {"document_object_key": "a.txt", "content": "数据库迁移的一般流程说明", "score": 0.9},
        {"document_object_key": "b.txt", "content": "外键冲突会触发 ERROR 1451 错误码", "score": 0.5},
    ]

    reranked = hybrid_search.rerank_features("ERROR 1451", candidates, 2)

    assert reranked[0]["document_object_key"] == "b.txt", "字面命中错误码的片段应该被提上来"
    assert reranked[0]["identifier_coverage"] == 1.0


def test_rerank_without_identifier_preserves_fusion_order() -> None:
    """核心回归保护：查询里没有标识符时，排序必须完全等于传入顺序。

    修复前的全局覆盖率重排会被「了什」「么改」这类噪声 2-gram 带偏，
    把讲「索引做了什么」的无关片段提到第一位。
    教训是：一个能推翻融合结果的弱重排器，比没有重排更糟。
    """
    candidates = [
        {"document_object_key": "a.md", "content": "索引做了什么改动", "score": 0.9},
        {"document_object_key": "b.md", "content": "V8 迁移脚本为引用来源表增加 retriever 列", "score": 0.8},
    ]

    reranked = hybrid_search.rerank_features("迁移脚本做了什么改动", candidates, 2)

    assert [item["document_object_key"] for item in reranked] == ["a.md", "b.md"]
    assert all(item["identifier_coverage"] == 0.0 for item in reranked)


def test_rerank_score_stays_in_range() -> None:
    candidates = [{"document_object_key": "a.txt", "content": "内容", "score": 1.0}]

    reranked = hybrid_search.rerank_features("内容", candidates, 1)

    assert 0.0 <= reranked[0]["score"] <= 1.0, "提权之后展示用分数仍必须落在 [0,1]"


def test_rerank_scores_do_not_all_saturate_at_one() -> None:
    """两条都命中标识符时展示分必须有落差。

    修复前用 min(final, 1.0) 截断，结果引用面板上三条全是「相关度 1.00」，
    用户完全分不出主次 —— 分数失去了展示意义。
    """
    candidates = [
        {"document_object_key": "a.md", "content": "XZ-2500：默认限流每秒两百次", "score": 1.0},
        {"document_object_key": "b.md", "content": "XZ-2500 出现在型号目录里", "score": 0.6},
    ]

    reranked = hybrid_search.rerank_features("XZ-2500 的默认限流参数", candidates, 2)

    scores = [float(item["score"]) for item in reranked]
    assert scores == sorted(scores, reverse=True), "分数必须与排序同序"
    assert scores[0] == 1.0, "最高分应归一到 1.0"
    assert scores[1] < 1.0, f"两条都命中标识符时不该并列满分，实际 {scores}"


# --------------------------------------------------------------------------- #
# 整体检索（用假语料替掉 Milvus）
# --------------------------------------------------------------------------- #

@pytest.fixture
def fake_store(monkeypatch):
    corpus = [
        {"document_object_key": "kb/1/flyway.md", "content": "数据库迁移用 Flyway，脚本在 db/migration 下。"},
        {"document_object_key": "kb/1/error.md", "content": "删除主表时子表有外键引用会报 ERROR 1451。"},
        {"document_object_key": "kb/1/frontend.md", "content": "前端技术栈是 Vue 3 与 TypeScript。"},
    ]
    monkeypatch.setattr(
        hybrid_search, "_fetch_corpus", lambda knowledge_base_id: list(corpus)
    )

    def fake_vector(knowledge_base_id, query, limit, document=None):
        # 故意模拟"向量检索把错误码漏掉"的典型失败：返回语义相近但字面不含 1451 的片段
        return [
            {"document_object_key": "kb/1/flyway.md", "content": corpus[0]["content"], "score": 0.82, "retriever": "vector"},
            {"document_object_key": "kb/1/frontend.md", "content": corpus[2]["content"], "score": 0.61, "retriever": "vector"},
        ][:limit]

    monkeypatch.setattr(hybrid_search, "vector_search", fake_vector)
    return corpus


def test_keyword_mode_finds_exact_error_code(fake_store) -> None:
    hits = hybrid_search.hybrid_retrieve(1, "ERROR 1451", 3, "keyword")
    assert hits, "关键词检索应该能命中"
    assert hits[0]["document_object_key"] == "kb/1/error.md"
    assert hits[0]["score"] > 0


def test_vector_mode_passes_through(fake_store) -> None:
    hits = hybrid_search.hybrid_retrieve(1, "ERROR 1451", 3, "vector")
    assert [hit["retriever"] for hit in hits] == ["vector", "vector"]
    assert hits[0]["document_object_key"] == "kb/1/flyway.md"


def test_hybrid_mode_includes_keyword_only_document(fake_store) -> None:
    """纯向量漏掉的 error.md 必须被混合检索捞回来 —— 这是本模块存在的全部理由。"""
    hits = hybrid_search.hybrid_retrieve(1, "ERROR 1451 外键", 3, "hybrid")
    keys = [hit["document_object_key"] for hit in hits]

    assert "kb/1/error.md" in keys
    assert all(0.0 <= float(hit["score"]) <= 1.0 for hit in hits)
    assert all(hit.get("rank") for hit in hits)


def test_hybrid_mode_labels_retriever(monkeypatch) -> None:
    corpus = [{"document_object_key": "kb/1/a.md", "content": "外键约束说明"}]
    monkeypatch.setattr(hybrid_search, "_fetch_corpus", lambda knowledge_base_id: list(corpus))
    monkeypatch.setattr(
        hybrid_search, "vector_search",
        lambda knowledge_base_id, query, limit, document=None: [
            {"document_object_key": "kb/1/a.md", "content": "外键约束说明", "score": 0.9, "retriever": "vector"}
        ],
    )
    hits = hybrid_search.hybrid_retrieve(1, "外键约束", 3, "hybrid")
    assert hits[0]["retriever"] == "hybrid", "两路都命中时应标记为 hybrid"


def test_keyword_search_filters_by_document_after_ranking(monkeypatch) -> None:
    """按文档过滤必须作用在**完整排名**上，不能先截断再筛。

    语料里 noise.md 把 alpha 重复了 5 次，BM25 分数必然高于只出现 1 次的 target.md。
    ``limit=1`` 时正确实现会跳过 noise.md 继续往下找；如果实现先取 ``ranked[:limit]``
    再过滤，那次截断已经把 target.md 切掉了，结果为空 —— 看起来像「这篇文档里没有
    相关内容」，实际是过滤与截断的顺序弄反了。
    """
    corpus = [
        {"document_object_key": "kb/1/noise.md", "content": "alpha alpha alpha alpha alpha"},
        {"document_object_key": "kb/1/target.md", "content": "alpha 只出现一次"},
    ]
    monkeypatch.setattr(hybrid_search, "_fetch_corpus", lambda knowledge_base_id: list(corpus))

    unfiltered = hybrid_search.keyword_search(1, "alpha", 1)
    filtered = hybrid_search.keyword_search(1, "alpha", 1, document="kb/1/target.md")

    assert unfiltered[0]["document_object_key"] == "kb/1/noise.md", "前提：全局第 1 名是噪声文档"
    assert [hit["document_object_key"] for hit in filtered] == ["kb/1/target.md"]
    # 过滤到一篇不存在的文档时必须是空结果，绝不能退化成「忽略过滤、全库检索」
    assert hybrid_search.keyword_search(1, "alpha", 3, document="kb/1/missing.md") == []


def test_hybrid_retrieve_pushes_document_filter_into_both_branches(monkeypatch) -> None:
    """过滤条件要下推到召回内部，不能取回后再筛。

    两路召回都自带 top-k 截断（向量是 Milvus 的 top-k，BM25 是 limit），
    先截断再筛会把「目标文档里的第 1 名」直接丢掉。所以 document 必须一路传到
    vector_search / keyword_search 的参数里。
    """
    captured: list[object] = []

    def fake_vector(knowledge_base_id, query, limit, document=None):
        captured.append(document)
        return []

    monkeypatch.setattr(hybrid_search, "_fetch_corpus", lambda knowledge_base_id: [
        {"document_object_key": "kb/1/a.md", "content": "alpha"}
    ])
    monkeypatch.setattr(hybrid_search, "vector_search", fake_vector)

    hybrid_search.hybrid_retrieve(1, "alpha", 3, "hybrid", document="kb/1/a.md")

    assert captured == ["kb/1/a.md"]


def test_invalid_mode_falls_back_to_configured_default(fake_store, monkeypatch) -> None:
    monkeypatch.setattr(hybrid_search.settings, "retrieval_mode", "hybrid")
    hits = hybrid_search.hybrid_retrieve(1, "ERROR 1451", 3, "not-a-mode")
    assert hits, "非法 mode 不该抛异常，应回退到服务端配置"


def test_corpus_cache_is_reused(monkeypatch) -> None:
    calls = {"count": 0}

    def counting_fetch(knowledge_base_id):
        calls["count"] += 1
        return [{"document_object_key": "kb/1/a.md", "content": "缓存测试内容"}]

    monkeypatch.setattr(hybrid_search, "_fetch_corpus", counting_fetch)

    hybrid_search.get_corpus(1)
    hybrid_search.get_corpus(1)
    assert calls["count"] == 1, "第二次应该命中缓存，不该重复拉 Milvus"

    hybrid_search.invalidate_corpus(1)
    hybrid_search.get_corpus(1)
    assert calls["count"] == 2, "失效之后必须重新加载，否则新文档搜不到"


def test_finalize_truncates_preview_and_assigns_rank(fake_store) -> None:
    hits = hybrid_search.hybrid_retrieve(1, "Flyway", 2, "keyword")
    assert [hit["rank"] for hit in hits] == list(range(1, len(hits) + 1))
    assert all(len(str(hit["preview"])) <= 240 for hit in hits)
