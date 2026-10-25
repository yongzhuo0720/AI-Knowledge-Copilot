"""评估脚本判分逻辑的单元测试。

判分逻辑决定所有指标是否可信，所以它本身必须被测。这里不连 Milvus、不跑检索，
只验证「什么样的片段算命中」这一件事。
"""

import importlib.util
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "evaluate_retrieval.py"
_spec = importlib.util.spec_from_file_location("evaluate_retrieval", _SCRIPT)
assert _spec and _spec.loader
evaluate_retrieval = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(evaluate_retrieval)


def _hit(content: str) -> dict:
    return {"content": content, "document_object_key": "kb/1/a.md"}


# --------------------------------------------------------------------------- #
# 判分依据的解析
# --------------------------------------------------------------------------- #

def test_span_as_plain_string() -> None:
    assert evaluate_retrieval._span_alternatives({"answer_span": "1451"}) == [["1451"]]


def test_span_as_list_requires_all_terms_in_one_span() -> None:
    """数组 = 同一备选内全部出现，所以是一组放进同一个备选里。"""
    assert evaluate_retrieval._span_alternatives({"answer_span": ["2500", "每秒两百次"]}) == [
        ["2500", "每秒两百次"]
    ]


def test_answer_any_creates_multiple_alternatives() -> None:
    item = {"answer_any": [["名次", "不使用分数"], ["名次", "倒数后相加"]]}
    assert evaluate_retrieval._span_alternatives(item) == [
        ["名次", "不使用分数"],
        ["名次", "倒数后相加"],
    ]


def test_answer_any_accepts_bare_string_option() -> None:
    assert evaluate_retrieval._span_alternatives({"answer_any": ["整体回滚"]}) == [["整体回滚"]]


def test_missing_span_yields_no_alternatives() -> None:
    assert evaluate_retrieval._span_alternatives({}) == []


# --------------------------------------------------------------------------- #
# 命中判定
# --------------------------------------------------------------------------- #

def test_rank_counts_from_one() -> None:
    alternatives = evaluate_retrieval._span_alternatives({"answer_span": "1451"})
    hits = [_hit("无关内容"), _hit("ERROR 1451 外键引用")]

    assert evaluate_retrieval._rank_of_first_match(hits, alternatives) == 2


def test_rank_is_none_when_nothing_matches() -> None:
    alternatives = evaluate_retrieval._span_alternatives({"answer_span": "1451"})
    assert evaluate_retrieval._rank_of_first_match([_hit("无关内容")], alternatives) is None


def test_identifier_alone_is_not_enough() -> None:
    """核心回归保护：只出现编号、没出现答案文本，不算命中。

    型号目录里有一条只写着 "XZ-2500" 的片段，它并没有回答「默认限流参数是多少」。
    只认编号时这种片段会被判成命中，指标虚高。
    """
    alternatives = evaluate_retrieval._span_alternatives(
        {"answer_span": ["2500", "每秒两百次"]}
    )
    hits = [_hit("XZ-2500 出现在型号目录里"), _hit("XZ-2500：默认限流每秒两百次")]

    assert evaluate_retrieval._rank_of_first_match(hits, alternatives) == 2


def test_answer_text_alone_is_not_enough() -> None:
    """反向也要挡住：只出现答案文本、没有编号，同样不算命中。"""
    alternatives = evaluate_retrieval._span_alternatives(
        {"answer_span": ["2500", "每秒两百次"]}
    )
    hits = [_hit("这款设备默认限流每秒两百次")]

    assert evaluate_retrieval._rank_of_first_match(hits, alternatives) is None


def test_matching_ignores_case() -> None:
    alternatives = evaluate_retrieval._span_alternatives(
        {"answer_span": ["V8__ADD_SOURCE_RETRIEVER"]}
    )
    assert evaluate_retrieval._rank_of_first_match([_hit("v8__add_source_retriever")], alternatives) == 1


def test_any_alternative_is_accepted() -> None:
    """同一问题有两个都正确的片段时，挑中任何一个都该算对。"""
    alternatives = evaluate_retrieval._span_alternatives(
        {"answer_any": [["名次", "不使用分数"], ["名次", "倒数后相加"]]}
    )

    assert evaluate_retrieval._rank_of_first_match([_hit("把两路的名次做倒数后相加")], alternatives) == 1
    assert evaluate_retrieval._rank_of_first_match([_hit("只使用名次不使用分数")], alternatives) == 1
    assert evaluate_retrieval._rank_of_first_match([_hit("与检索无关的片段")], alternatives) is None


def test_empty_alternatives_never_match() -> None:
    assert evaluate_retrieval._rank_of_first_match([_hit("任意内容")], []) is None


# --------------------------------------------------------------------------- #
# 指标汇总
# --------------------------------------------------------------------------- #

def test_summarize_computes_mrr_and_hit1() -> None:
    metrics = evaluate_retrieval._summarize([
        {"rank": 1, "kind": "exact-code"},
        {"rank": 2, "kind": "exact-code"},
        {"rank": None, "kind": "exact-code"},
        {"rank": 1, "kind": "exact-code"},
    ])

    assert metrics["total"] == 4
    assert metrics["recall"] == pytest.approx(0.75), "4 题里 3 题召回"
    assert metrics["hit1"] == pytest.approx(0.5), "4 题里 2 题排第一"
    assert metrics["mrr"] == pytest.approx((1 + 0.5 + 0 + 1) / 4), "未召回的题目按 0 计入分母"


# --------------------------------------------------------------------------- #
# 模式列表与「改写是否变好」的判定
# --------------------------------------------------------------------------- #

def test_modes_include_rewrite_so_it_is_compared_in_the_same_table() -> None:
    """改写（Multi-Query）与远端重排必须和另外几档并排跑。

    如果给它们另开一张表、另跑一批题，那「有没有用」就变成了两次不同实验的对比，
    说服力会大打折扣。这条断言钉住的是「它们在同一个 MODES 里」。
    （没配远端 key 时 evaluate() 会把 rerank 列摘掉，避免静默退化成 hybrid 假数据。）
    """
    assert list(evaluate_retrieval.MODES) == ["vector", "keyword", "hybrid", "rewrite", "rerank"]


def test_better_treats_a_miss_as_the_worst_outcome() -> None:
    better = evaluate_retrieval._better

    assert better(1, 3) is True, "从第 3 提到第 1 是变好"
    assert better(3, 1) is False, "从第 1 掉到第 3 是变差"
    assert better(2, 2) is False, "一样就不是变好"
    assert better(None, 3) is False, "没召回不可能是变好"
    assert better(3, None) is True, "从「完全没召回」变成召回，哪怕排第 3 也是变好"
    assert better(None, None) is False


def test_rank_text_is_readable_for_misses() -> None:
    assert evaluate_retrieval._rank_text(None) == "MISS"
    assert evaluate_retrieval._rank_text(2) == "#2"
