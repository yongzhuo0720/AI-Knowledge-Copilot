"""共享判分口径（``app/eval_judging``）的单元测试。

这个模块是「评估」与「索引自检」共用的唯一判分实现，所以它必须被独立测。
测试覆盖三件事：备选归一化、单片段命中判定、多候选筛选。
"""

from app.eval_judging import matched_candidates, matches, rank_of_first_match, span_alternatives, summarize


# --------------------------------------------------------------------------- #
# matches / matched_candidates
# --------------------------------------------------------------------------- #

def test_matches_requires_every_term_of_one_alternative() -> None:
    lowered = [["2500", "每秒两百次"]]

    assert matches("XZ-2500 默认限流每秒两百次", lowered) is True
    assert matches("XZ-2500 出现在型号目录里", lowered) is False, "只有编号不够"
    assert matches("这款设备默认限流每秒两百次", lowered) is False, "只有答案文本不够"


def test_matches_is_case_insensitive_for_identifiers() -> None:
    """调用方不需要预处理大小写 —— 忘了小写化不会报错，只会静默漏匹配。"""
    assert matches("v8__add_source_retriever", [["V8__ADD_SOURCE_RETRIEVER"]]) is True
    assert matches("V8__ADD_SOURCE_RETRIEVER", [["v8__add_source_retriever"]]) is True


def test_matches_without_alternatives_is_false() -> None:
    assert matches("任意内容", []) is False


def test_matched_candidates_returns_all_hits_not_just_the_first() -> None:
    """自检需要知道「答案落在哪几篇文档里」，所以必须返回全部命中而非第一条。"""
    candidates = [
        {"object_key": "kb/31/a.md", "content": "外键冲突会让当前事务整体回滚"},
        {"object_key": "kb/31/b.md", "content": "无关内容"},
        {"object_key": "kb/31/c.md", "content": "多数约束类错误会导致当前事务回滚"},
    ]
    alternatives = span_alternatives({
        "answer_any": [["整体回滚"], ["约束类错误", "事务回滚"]],
    })

    found = matched_candidates(candidates, alternatives)

    assert [item["object_key"] for item in found] == ["kb/31/a.md", "kb/31/c.md"]


def test_matched_candidates_is_empty_without_alternatives() -> None:
    assert matched_candidates([{"content": "任意内容"}], []) == []


def test_matched_candidates_treats_missing_content_as_no_match() -> None:
    assert matched_candidates([{"object_key": "kb/31/a.md"}], [["x"]]) == []


def test_rank_and_matched_candidates_agree() -> None:
    """两条 API 必须一致：rank 返回的位次，其对应候选一定在 matched 里。"""
    candidates = [{"content": "无关"}, {"content": "ERROR 1451 外键引用"}]
    alternatives = span_alternatives({"answer_span": ["1451", "外键引用"]})

    rank = rank_of_first_match(candidates, alternatives)
    found = matched_candidates(candidates, alternatives)

    assert rank == 2
    assert found == [candidates[1]]


# --------------------------------------------------------------------------- #
# summarize
# --------------------------------------------------------------------------- #

def test_summarize_counts_unrecalled_questions_in_mrr_denominator() -> None:
    """未召回的题必须按 0 计入分母，否则「没召回」和「排第 1」会被算成同一件事。"""
    metrics = summarize([{"rank": 1}, {"rank": None}])

    assert metrics["recall"] == 0.5
    assert metrics["hit1"] == 0.5
    assert metrics["mrr"] == 0.5, "(1 + 0) / 2"


def test_summarize_handles_empty_input() -> None:
    metrics = summarize([])

    assert metrics["total"] == 0
    assert metrics["recall"] == 0.0
