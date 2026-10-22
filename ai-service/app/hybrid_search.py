"""混合检索：向量召回 + BM25 关键词召回 → RRF 融合 → 可选重排。

## 为什么要做混合检索

纯向量召回有一个结构性弱点：**对专有名词、编号、缩写不敏感**。

向量是把整段文本压成一个语义点，模型在训练时见到"ERROR 1451"这类字符串的次数极少，
所以它的向量方向和"数据库报错""外键冲突"高度接近——语义是对的，但排序会把
**含不含这个编号**的片段混在一起。检索"V7 迁移脚本"时，可能召回一堆讲迁移的段落，
却没把真正写着 V7 的那段排前面。

而企业知识库里恰恰全是这些：工单号、错误码、版本号、类名、配置项名。
BM25 靠精确词频匹配，正好补上这块。两者一个管"意思对"，一个管"字面命中"。

## 融合为什么用 RRF 而不是加权求和

两路召回的分数量纲完全不同：Milvus 的 IP 相似度大致落在 [0,1]，BM25 是**无上界**的
（取决于词频与 IDF）。直接加权求和必须先做归一化，而归一化本身会引入新的偏差
（比如把 BM25 的最高分强行拉到 1，等于假设第一名一定完美）。

RRF（Reciprocal Rank Fusion）只用**排名**不用分数：

    score(d) = Σ_r  1 / (k + rank_r(d))      # k 通常取 60

好处是天然免疫量纲问题、无需调参、对单个召回路的偶发噪声稳健。
代价是丢掉了"分数差距"信息（第 1 名比第 2 名好多少，RRF 不关心）。
在企业知识库这种"宁滥勿缺"的场景下，这是值得的取舍。

## 重排

RRF 解决的是"两路怎么合"，重排解决的是"合完之后哪几条真的最相关"。
纯向量和 BM25 都是**双编码器**思路——query 和 doc 各自算表示，从不交互。
重排阶段把 query 和每个候选放一起逐条比较，精度更高。

本项目提供两种实现：
1. 配了 rerank API 时走真·Cross-Encoder（如 bge-reranker / Cohere rerank）；
2. 没配时用**加法提权的轻量重排** —— 只在查询里出现了标识符（编号/型号/版本号）
   时给命中它的片段加分，其余情况排序完全等于 RRF 融合结果。
   它**不是** Cross-Encoder，也刻意不做全局重排，原因见 ``rerank_features``。

## 多查询（Multi-Query）

上面三件事（双路召回 / 融合 / 重排）都只在**用户那一种表述**上工作。如果这个表述
恰好偏离了语料的用词，两路会一起失效。

:func:`multi_query_retrieve` 在这一整套之上再加一层：先用模型把问题改写成几种说法，
各跑一遍单查询检索，再对这几份排名做第二次 RRF。它**不改动** :func:`hybrid_retrieve`，
所以「多查询」开关关掉时，系统行为与没有这个功能时逐字节一致。详见 ``app/query_rewrite``。
"""

import hashlib
import logging
import math
import re
import threading
import time
from collections import Counter
from typing import Any

from app.settings import settings

logger = logging.getLogger(__name__)

CJK_RUN = re.compile(r"[\u4e00-\u9fff]+")
NON_CJK_RUN = re.compile(r"[^\u4e00-\u9fff]+")
ASCII_TOKEN = re.compile(r"[a-z0-9]+(?:[._\-/+][a-z0-9]+)*")
ASCII_PART = re.compile(r"[a-z0-9]+")
ASCII_SEPARATOR = re.compile(r"[._\-/+]")
CJK_CHAR = re.compile(r"[\u4e00-\u9fff]")

# jieba 是**可选**依赖，不是必需的。
#
# 它只发布源码包（sdist），装的时候要本地构建，而且自带 18MB 词典——
# 这两点都会让镜像构建变慢变脆。而 BM25 的关键词召回真正依赖的是
# "把中文切成可精确匹配的单元"，bigram 就能做到，且对未登录词（新错误码、
# 新型号）天然免疫。所以默认走 bigram，jieba 装了就用、能装上就用。
try:
    import jieba

    jieba.setLogLevel(logging.WARNING)
    _JIEBA: Any = jieba
except ImportError:  # pragma: no cover - 取决于运行环境
    _JIEBA = None


# --------------------------------------------------------------------------- #
# 分词
# --------------------------------------------------------------------------- #

def _ascii_tokens(text: str) -> list[str]:
    """抽出英文/数字标识符，并额外拆出它的组成部分。

    为什么必须拆：``V8__add_source_retriever`` 作为一个整体 token 时，
    用户输入 ``V8`` 切出的 token 是 ``v8``，两边**永远匹配不上** ——
    而这个 ``v8`` 恰恰是整条查询里最有区分度的词。BM25 和重排的词覆盖率
    都会因此给零分，正确答案的分数被拉低，最后被无关片段挤下去。

    拆解之后 ``v8``/``add``/``source``/``retriever`` 都会进倒排索引，
    整体 token 仍然保留，所以精确匹配长标识符的能力不受影响。
    """
    tokens: list[str] = []
    for token in ASCII_TOKEN.findall(text):
        tokens.append(token)
        if ASCII_SEPARATOR.search(token):
            tokens.extend(ASCII_PART.findall(token))
    return tokens


def _fallback_tokenize(text: str) -> list[str]:
    """无 jieba 时的降级分词：中文按「单字 + 2-gram」，英文数字按词。

    2-gram 是中文检索里非常经典的手段（Lucene 的 CJKAnalyzer 就是这么干的）。
    它不依赖词典，因此对"未登录词"——也就是新出现的术语、型号、缩写——天然免疫，
    这正是企业知识库的关键词检索最需要的性质。代价是索引体积变大。
    """
    tokens: list[str] = []
    for chunk in NON_CJK_RUN.findall(text):
        tokens.extend(_ascii_tokens(chunk))
    for run in CJK_RUN.findall(text):
        if len(run) == 1:
            # 只有单字时才输出单字 token，否则这个字就彻底搜不到了
            tokens.append(run)
        else:
            # 多字段落只输出 2-gram，**不输出单字**。
            # 输出单字看着能提高召回，实际是噪声：查询「外键约束」会被切成
            # 外/键/约/束，于是任何含「的」「约」的片段都能命中，排序立刻失控。
            # 2-gram 已经保证了「外键」「约束」这类真正有意义的词能被精确匹配。
            tokens.extend(run[index:index + 2] for index in range(len(run) - 1))
    return tokens


def tokenize(text: str) -> list[str]:
    """把文本切成检索用的 token。"""
    text = (text or "").lower()
    if not text:
        return []
    if _JIEBA is None:
        return _fallback_tokenize(text)

    tokens: list[str] = []
    # cut_for_search 会同时吐出长词和它的子词（"外键约束" → 外键/约束/外键约束），
    # 对召回率友好；代价是同一个词被重复索引，用 Counter 统计时无所谓。
    for piece in _JIEBA.cut_for_search(text):
        piece = piece.strip()
        if not piece:
            continue
        if CJK_CHAR.search(piece):
            tokens.append(piece)
            # 再补一层 2-gram：jieba 分词错了也不会彻底漏掉（比如把"外键约"切成一个词）
            if len(piece) > 2:
                tokens.extend(piece[index:index + 2] for index in range(len(piece) - 1))
        else:
            tokens.extend(_ascii_tokens(piece))
    return tokens


# --------------------------------------------------------------------------- #
# BM25
# --------------------------------------------------------------------------- #

class BM25Index:
    """BM25（Okapi）打分，带倒排索引。

    BM25 的核心是两件事：
    1. **TF 饱和** —— 一个词出现 10 次不该是出现 1 次的 10 倍价值，
        所以在分子上用 `tf*(k1+1)/(tf + k1*(...))` 做压缩，k1 控制饱和速度；
    2. **文档长度归一化** —— 长文档天然更容易命中词，得按长度惩罚，b 控制惩罚强度。

    IDF 用 `log(1 + (N - df + 0.5)/(df + 0.5))` 这个变体，好处是**恒为正**。
    原始公式在词出现在超过一半文档时会给出负值，等于"这个词出现反而是减分项"，
    对短小的知识库（几十个 chunk）很常见，是经典坑。
    """

    def __init__(self, documents: list[list[str]], k1: float | None = None, b: float | None = None) -> None:
        self.k1 = settings.bm25_k1 if k1 is None else k1
        self.b = settings.bm25_b if b is None else b
        self.doc_count = len(documents)
        self.term_frequencies = [Counter(tokens) for tokens in documents]
        self.doc_lengths = [sum(frequencies.values()) for frequencies in self.term_frequencies]
        self.average_length = (sum(self.doc_lengths) / self.doc_count) if self.doc_count else 0.0

        document_frequency: Counter[str] = Counter()
        for frequencies in self.term_frequencies:
            document_frequency.update(frequencies.keys())
        self.idf: dict[str, float] = {
            term: math.log(1.0 + (self.doc_count - frequency + 0.5) / (frequency + 0.5))
            for term, frequency in document_frequency.items()
        }
        # 倒排索引：term -> [(doc_index, tf)]，避免每个查询词都扫全库
        self.inverted: dict[str, list[tuple[int, int]]] = {}
        for index, frequencies in enumerate(self.term_frequencies):
            for term, frequency in frequencies.items():
                self.inverted.setdefault(term, []).append((index, frequency))

    def scores(self, query_tokens: list[str]) -> list[float]:
        result = [0.0] * self.doc_count
        if not self.doc_count or self.average_length == 0:
            return result
        for term in dict.fromkeys(query_tokens):  # 去重且保序
            idf = self.idf.get(term)
            if idf is None:
                continue
            for index, frequency in self.inverted.get(term, ()):
                length_ratio = self.doc_lengths[index] / self.average_length
                denominator = frequency + self.k1 * (1.0 - self.b + self.b * length_ratio)
                result[index] += idf * frequency * (self.k1 + 1.0) / denominator
        return result


# --------------------------------------------------------------------------- #
# 语料缓存
# --------------------------------------------------------------------------- #

# 关键词检索必须先把整个知识库的 chunk 拿进来建索引。
# 每次查询都重建代价太高（几千条 chunk 要反复扫 Milvus），
# 所以按知识库缓存，并在有新文档入库时主动失效。
_corpus_cache: dict[int, tuple[float, list[dict[str, str]], BM25Index]] = {}
_corpus_lock = threading.Lock()


def invalidate_corpus(knowledge_base_id: int | None = None) -> None:
    """让缓存失效。传 None 表示清空全部。"""
    with _corpus_lock:
        if knowledge_base_id is None:
            _corpus_cache.clear()
        else:
            _corpus_cache.pop(knowledge_base_id, None)


def _fetch_corpus(knowledge_base_id: int) -> list[dict[str, str]]:
    """从 Milvus 拉出该知识库的全部 chunk。

    分页交给 ``indexing.iter_all_rows``：它处理了「Milvus 的 query 有
    offset + limit ≤ 16384 的硬上限」这件事 —— 只按 offset 递增分页的话，
    语料一超过这个量，BM25 语料就整体加载失败（然后整条检索退化到纯向量），
    而小知识库上一切正常，非常难复现。
    """
    from pymilvus import Collection, connections, utility

    from app.indexing import COLLECTION_NAME, iter_all_rows

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return []
    collection = Collection(COLLECTION_NAME)
    collection.load()

    rows = iter_all_rows(collection, f"knowledge_base_id == {knowledge_base_id}", ["object_key", "content"])
    return [
        {"document_object_key": str(row.get("object_key") or ""), "content": str(row.get("content") or "")}
        for row in rows
    ]


def get_corpus(knowledge_base_id: int) -> tuple[list[dict[str, str]], BM25Index]:
    """取（已缓存的）语料与 BM25 索引。"""
    now = time.monotonic()
    with _corpus_lock:
        cached = _corpus_cache.get(knowledge_base_id)
        if cached and now - cached[0] < settings.corpus_cache_ttl_seconds:
            return cached[1], cached[2]

    # 注意：查询 Milvus 和建索引都在锁外做，避免把慢操作串行化。
    # 并发下可能有两个请求同时重建，结果一致，代价可接受。
    documents = _fetch_corpus(knowledge_base_id)
    index = BM25Index([tokenize(document["content"]) for document in documents])
    with _corpus_lock:
        _corpus_cache[knowledge_base_id] = (now, documents, index)
    return documents, index


# --------------------------------------------------------------------------- #
# 两路召回
# --------------------------------------------------------------------------- #

def _identity(document: dict[str, str]) -> str:
    raw = f"{document['document_object_key']}\u0000{document['content']}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def keyword_search(
    knowledge_base_id: int,
    query: str,
    limit: int,
    document: str | None = None,
) -> list[dict[str, object]]:
    """BM25 召回。

    ``document`` 过滤是在**排序之后、取前 limit 之前**做的，所以这里先遍历完整排名
    再按需 break，而不是一开始就 ``ranked[:limit]``。否则「某篇文档里的第 1 名」如果在
    全局排到 limit 之外，就会被截断掉，过滤后返回空列表 —— 看起来像「这篇文档里没有
    相关内容」，实际是过滤和截断的顺序弄反了。
    """
    documents, index = get_corpus(knowledge_base_id)
    if not documents:
        return []
    scores = index.scores(tokenize(query))
    ranked = sorted(range(len(documents)), key=lambda position: scores[position], reverse=True)
    hits: list[dict[str, object]] = []
    for position in ranked:
        if scores[position] <= 0:
            break  # 分数已为 0，再往后只会更低，没有继续扫的必要
        if document and documents[position]["document_object_key"] != document:
            continue
        hits.append({
            "document_object_key": documents[position]["document_object_key"],
            "content": documents[position]["content"],
            "score": round(scores[position], 6),
            "retriever": "keyword",
        })
        if len(hits) >= limit:
            break
    return hits


def vector_search(
    knowledge_base_id: int,
    query: str,
    limit: int,
    document: str | None = None,
) -> list[dict[str, object]]:
    from app.indexing import vector_search as raw_vector_search

    return raw_vector_search(knowledge_base_id, query, limit, document)


# --------------------------------------------------------------------------- #
# RRF 融合
# --------------------------------------------------------------------------- #

def reciprocal_rank_fusion(ranked_lists: list[list[str]], k: int = 60) -> dict[str, float]:
    """Reciprocal Rank Fusion：只用排名，不用原始分数。"""
    fused: dict[str, float] = {}
    for ranked in ranked_lists:
        for rank, key in enumerate(ranked):
            fused[key] = fused.get(key, 0.0) + 1.0 / (k + rank + 1)
    return fused


# --------------------------------------------------------------------------- #
# 重排
# --------------------------------------------------------------------------- #

IDENTIFIER_CHAR = re.compile(r"[a-z0-9]")

# 加法提权的权重。标识符命中是最强、最可靠的判别信号，权重给得比整串命中高。
# 这两个值只做「往上抬」，不能推翻融合结果的相对次序，所以不需要精细调参。
IDENTIFIER_WEIGHT = 0.35
PHRASE_WEIGHT = 0.15


def _identifier_tokens(query: str) -> set[str]:
    """挑出查询里的「标识符类」词元：编号、型号、版本号、类名等含字母数字的词。

    这些词是重排唯一可靠的判别信号 —— 它们几乎不会因为措辞不同而消失，
    命中就是命中。相反，中文的普通词元会被切出大量跨词边界的偶然 2-gram
    （「做了什么」→ 了什 / 么改），拿它们算覆盖率会把无关片段误判成高相关。
    """
    return {
        token for token in tokenize(query)
        if len(token) >= 2 and IDENTIFIER_CHAR.search(token)
    }


def rerank_features(
    query: str,
    candidates: list[dict[str, object]],
    top_n: int,
) -> list[dict[str, object]]:
    """轻量重排：**只做提权，不做全局重排序**。

    这不是 Cross-Encoder，而是一个刻意的保守设计。原因来自实测：

    最初版本用「IDF 加权词覆盖率」对候选整体重新打分排序，结果在某些问题上
    反而把正确答案挤下去了 —— 查询「V8 迁移脚本做了什么改动」会被切出
    「了什」「么改」这类跨词边界的噪声 2-gram，而另一篇讲索引的文档标题恰好是
    「索引做了什么」，靠噪声匹配拿到虚高的覆盖率，被提到第一位。

    教训很清楚：**一个能推翻融合结果的弱重排器，比没有重排更糟**。
    所以这里改成加法提权：

        final = 原融合分 + 标识符命中加成 + 整串命中加成

    查询里没有标识符时，加成恒为 0，排序完全等于融合结果 —— 也就不会退化。
    查询里有标识符（错误码、型号、版本号）时，命中它的片段被抬上来，
    这正是关键词召回存在的意义。
    """
    identifier_tokens = _identifier_tokens(query)
    normalized_query = re.sub(r"\s+", "", query).lower()
    rescored: list[dict[str, object]] = []

    for candidate in candidates:
        content = str(candidate.get("content") or "")
        prior = float(candidate.get("score") or 0.0)  # 归一化到 [0,1] 的融合分
        identifier_bonus = 0.0
        if identifier_tokens:
            document_tokens = set(tokenize(content))
            identifier_bonus = len(identifier_tokens & document_tokens) / len(identifier_tokens)
        phrase_bonus = 1.0 if normalized_query and normalized_query in re.sub(r"\s+", "", content).lower() else 0.0
        final = prior + IDENTIFIER_WEIGHT * identifier_bonus + PHRASE_WEIGHT * phrase_bonus
        rescored.append({
            **candidate,
            # score 在排序后统一按最高分缩放，这里先占位
            "rerank_score": round(final, 4),
            "identifier_coverage": round(identifier_bonus, 4),
        })

    rescored.sort(key=lambda item: float(item["rerank_score"]), reverse=True)
    rescored = rescored[:top_n]

    # 展示用分数按本轮最高分重新缩放，而不是直接截断到 1.0。
    # 直接 min(final, 1.0) 会让**所有**命中标识符的片段并列 1.00 ——
    # 前端引用面板上三条"相关度 1.00"完全分不出主次。按最高分缩放既保持
    # 「排序与分数同序」，又让名次之间有可读的落差。
    ceiling = max((float(item["rerank_score"]) for item in rescored), default=0.0)
    for item in rescored:
        scaled = float(item["rerank_score"]) / ceiling if ceiling > 0 else 0.0
        item["score"] = round(max(0.0, min(scaled, 1.0)), 4)
    return rescored


def _api_rerank(query: str, candidates: list[dict[str, object]], top_n: int) -> list[dict[str, object]]:
    """调用外部重排服务（Cohere / Jina / bge-reranker 兼容的 /rerank 接口）。"""
    import json
    from urllib.request import Request, urlopen

    payload = {
        "model": settings.rerank_model,
        "query": query,
        "documents": [str(candidate.get("content") or "") for candidate in candidates],
        "top_n": top_n,
    }
    request = Request(
        f"{settings.rerank_base_url.rstrip('/')}/rerank",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": f"Bearer {settings.rerank_api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        body = json.load(response)
    results = body.get("results") or body.get("data") or []
    reranked: list[dict[str, object]] = []
    for item in results[:top_n]:
        position = int(item.get("index", 0))
        if 0 <= position < len(candidates):
            reranked.append({
                **candidates[position],
                "score": round(float(item.get("relevance_score", 0.0)), 4),
                "rerank_score": round(float(item.get("relevance_score", 0.0)), 4),
            })
    return reranked or candidates[:top_n]


def rerank(query: str, candidates: list[dict[str, object]], top_n: int) -> list[dict[str, object]]:
    """重排入口。

    配了外部重排服务（真正的 Cross-Encoder）就用它，否则退回到本地保守提权。
    两条路都不允许把异常抛给调用方 —— 重排只是锦上添花，不该拖垮问答主链路。
    """
    if not candidates:
        return []
    if settings.rerank_enabled and settings.rerank_api_key and settings.rerank_base_url:
        try:
            return _api_rerank(query, candidates, top_n)
        except Exception:
            logger.warning("remote rerank failed; falling back to local heuristic", exc_info=True)
    return rerank_features(query, candidates, top_n)


# --------------------------------------------------------------------------- #
# 对外入口
# --------------------------------------------------------------------------- #

def _normalize(scores: dict[str, float]) -> dict[str, float]:
    if not scores:
        return {}
    top = max(scores.values())
    if top <= 0:
        return {key: 0.0 for key in scores}
    return {key: value / top for key, value in scores.items()}


def hybrid_retrieve(
    knowledge_base_id: int,
    query: str,
    limit: int,
    mode: str | None = None,
    document: str | None = None,
) -> list[dict[str, object]]:
    """统一检索入口。

    :param mode: ``vector`` 纯向量 / ``keyword`` 纯 BM25 / ``hybrid`` 混合（默认取配置）
    :param document: 只在这个 object_key 的文档内检索；``None`` 表示整个知识库
    """
    mode = (mode or settings.retrieval_mode or "hybrid").lower()
    if mode not in {"vector", "keyword", "hybrid"}:
        mode = "hybrid"

    if mode == "vector":
        return _finalize(vector_search(knowledge_base_id, query, limit, document), limit)
    if mode == "keyword":
        return _finalize(keyword_search(knowledge_base_id, query, limit, document), limit)

    pool = max(limit * 4, settings.retrieval_candidate_pool)
    vector_hits = vector_search(knowledge_base_id, query, pool, document)
    keyword_hits = keyword_search(knowledge_base_id, query, pool, document)

    # 单路为空时直接退化成另一路，别让融合放大"另一路是噪声"的问题
    if not vector_hits:
        return _finalize(keyword_hits[:limit], limit)
    if not keyword_hits:
        return _finalize(vector_hits[:limit], limit)

    vector_keys = [_identity(hit) for hit in vector_hits]
    keyword_keys = [_identity(hit) for hit in keyword_hits]

    documents: dict[str, dict[str, object]] = {}
    for key, hit in zip(vector_keys + keyword_keys, vector_hits + keyword_hits):
        documents.setdefault(key, hit)
    # 命中来源在合并阶段就一次算好，避免后面每条再做 O(n) 的查找
    vector_key_set = set(vector_keys)
    keyword_key_set = set(keyword_keys)

    fused = reciprocal_rank_fusion([vector_keys, keyword_keys])
    normalized = _normalize(fused)

    merged: list[dict[str, object]] = []
    for key, fusion_score in normalized.items():
        raw = dict(documents[key])
        raw["score"] = round(fusion_score, 4)
        raw["retriever"] = _retriever_label(key, vector_key_set, keyword_key_set)
        raw["fusion_score"] = round(fusion_score, 4)
        raw["rrf_score"] = round(fused[key], 6)
        merged.append(raw)
    merged.sort(key=lambda item: float(item["fusion_score"]), reverse=True)

    candidates = merged[:pool]
    if not settings.rerank_enabled:
        return _finalize(candidates[:limit], limit)

    return _finalize(rerank(query, candidates, limit), limit)


def _retriever_label(key: str, vector_keys: set[str], keyword_keys: set[str]) -> str:
    """标记这条片段是被哪一路召回的 —— 前端会展示，也是验证混合检索生效的直接证据。"""
    in_vector = key in vector_keys
    in_keyword = key in keyword_keys
    if in_vector and in_keyword:
        return "hybrid"
    return "vector" if in_vector else "keyword"


def _finalize(hits: list[dict[str, object]], limit: int) -> list[dict[str, object]]:
    """统一出参：score 归一到 [0,1]，并保证必需字段齐全。"""
    trimmed = hits[:limit]
    for rank, hit in enumerate(trimmed):
        hit.setdefault("retriever", "vector")
        hit.setdefault("rank", rank + 1)
        content = str(hit.get("content") or "")
        # 展示用：太长的片段截断，避免把整个 chunk 塞进引用面板
        hit["preview"] = content[:240]
    return trimmed


# --------------------------------------------------------------------------- #
# 多查询（Multi-Query）
# --------------------------------------------------------------------------- #

def multi_query_retrieve(
    knowledge_base_id: int,
    query: str,
    limit: int,
    mode: str | None = None,
    document: str | None = None,
    rewrites: list[str] | None = None,
) -> list[dict[str, object]]:
    """先改写再检索：对原问题和它的若干改写各跑一遍，第二次 RRF 融合。

    这是架在 :func:`hybrid_retrieve` **之上**的一层，而不是把它改掉：

    1. 原问题 + N 条改写，每条各跑一遍完整的单查询检索（双路召回 + 融合 + 重排），
       得到 N+1 份排名；
    2. 对这 N+1 份排名再做一次 RRF —— RRF 只看名次，所以「每份排名内部是怎么排的」
       并不影响它的正确性，单查询那条链路因此**一行都不用改**；
    3. 用**原问题**重排。

    :param rewrites: 显式指定改写结果。传空列表表示「只跑原问题」，
        传 ``None`` 表示「按配置自己改写」。测试与评估脚本靠它绕开模型调用。

    第 3 步用原问题而不是某条改写来重排，是一个刻意的选择：
    **改写是为了「多召回」，相关性仍然该由用户真正问的那句话定义。**
    拿某条改写去重排，等于让模型替用户决定什么算相关。
    """
    if rewrites is None:
        from app.query_rewrite import rewrite_query

        rewrites = rewrite_query(query)

    variants = [query] + [item for item in rewrites if item.strip()]
    if len(variants) == 1:
        # 一条改写都没有 —— 这就是「没有这个功能」时的行为，不要多跑一次检索。
        return hybrid_retrieve(knowledge_base_id, query, limit, mode, document)

    pool = max(limit * 4, settings.retrieval_candidate_pool)

    ranked_lists: list[list[str]] = []
    documents: dict[str, dict[str, object]] = {}
    for variant in variants:
        hits = hybrid_retrieve(knowledge_base_id, variant, pool, mode, document)
        keys: list[str] = []
        for hit in hits:
            key = _identity(hit)
            keys.append(key)
            # 同一片段被多条改写召回时，保留最先出现的那份原始数据。
            # 各条改写的召回结果里 content / object_key 必然一致，差别只在 score，
            # 而 score 马上会被融合分覆盖，所以取哪一份都行。
            documents.setdefault(key, hit)
        ranked_lists.append(keys)

    if not documents:
        return []

    fused = reciprocal_rank_fusion(ranked_lists)
    normalized = _normalize(fused)
    # 每条片段被几种说法召回。一次算好并缓存，供排序后一次性写回，
    # 避免在循环里对每份排名做一次 O(n) 查找。
    recalled_by = Counter(key for keys in ranked_lists for key in keys)

    merged: list[dict[str, object]] = []
    for key, fusion_score in normalized.items():
        raw = dict(documents[key])
        raw["score"] = round(fusion_score, 4)
        raw["fusion_score"] = round(fusion_score, 4)
        raw["rrf_score"] = round(fused[key], 6)
        # 多查询独有的信号：被越多种说法命中，越可能是「答案」而不是「碰巧词面像」。
        # 评估脚本与前端的引用面板都可以直接用它解释「这条为什么被抬上来」。
        raw["rewrite_hits"] = recalled_by[key]
        # 每条改写的排名里都带 retriever 标签，但融合之后「是向量还是关键词召回的」
        # 这个信息在跨改写合并时已经没有意义了，统一标记为 multi-query。
        raw["retriever"] = "multi-query"
        merged.append(raw)

    merged.sort(key=lambda item: float(item["fusion_score"]), reverse=True)
    candidates = merged[:pool]

    if not settings.rerank_enabled:
        return _finalize(candidates[:limit], limit)

    return _finalize(rerank(query, candidates, limit), limit)
