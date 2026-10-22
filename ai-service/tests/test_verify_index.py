"""索引自检脚本的单元测试。

只测「不碰外部依赖」的部分：把 chunk 列表直接喂进去，验证「判分串在库里能不能字面命中」
这一件事。这里的判分口径与评估脚本共用 ``app.eval_judging``，所以本测试同时保护了
一条重要不变量：**自检和评估必须用同一套判分逻辑**。两者一旦漂移，就会出现
「评估说 MISS、自检说可答」这种自相矛盾的结果。
"""

import importlib.util
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "verify_index.py"
_spec = importlib.util.spec_from_file_location("verify_index", _SCRIPT)
assert _spec and _spec.loader
verify_index = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verify_index)


def _chunk(content: str, name: str = "kb/31/x/doc.md") -> dict:
    return {"object_key": name, "content": content}


def _payload(*questions: dict) -> dict:
    return {"questions": list(questions)}


def test_answer_present_is_reported_as_available() -> None:
    payload = _payload({"question": "ERROR 1451 是什么原因导致的", "answer_span": ["1451", "外键引用"]})
    absent = verify_index.check_answers(payload, [_chunk("ERROR 1451 外键引用约束失败")])

    assert absent == []


def test_answer_missing_is_reported_with_expectation() -> None:
    payload = _payload({
        "question": "覆盖索引为什么能变快",
        "answer_span": ["覆盖索引", "不必回到主表取数据"],
        "expect_doc": "index-basics.md",
    })
    absent = verify_index.check_answers(payload, [_chunk("无关内容")])

    assert len(absent) == 1
    assert absent[0]["question"] == "覆盖索引为什么能变快"
    assert absent[0]["expect_doc"] == "index-basics.md"
    assert absent[0]["alternatives"] == [["覆盖索引", "不必回到主表取数据"]]


def test_list_span_requires_all_terms_in_same_chunk() -> None:
    """两个词分散在两个 chunk 里不算命中 —— 判分要求它们落在同一片段内。"""
    payload = _payload({"question": "XZ-2500 的默认限流参数", "answer_span": ["2500", "每秒两百次"]})
    absent = verify_index.check_answers(payload, [_chunk("型号 XZ-2500"), _chunk("默认限流每秒两百次")])

    assert len(absent) == 1


def test_answer_any_accepts_either_alternative() -> None:
    payload = _payload({
        "question": "外键约束冲突会让事务回滚吗",
        "answer_any": [["整体回滚"], ["约束类错误", "事务回滚"]],
    })

    assert verify_index.check_answers(payload, [_chunk("外键冲突会让当前事务整体回滚")]) == []
    assert verify_index.check_answers(payload, [_chunk("多数约束类错误会导致当前事务回滚")]) == []


def test_report_uses_chunk_file_name_not_full_key() -> None:
    """自检报告里的文档名要去掉 object_key 前缀，否则和评估集的 corpus 名字对不上。"""
    payload = _payload({"question": "怎么定位慢查询", "answer_span": "慢查询日志"})
    absent = verify_index.check_answers(payload, [_chunk("打开慢查询日志", "kb/31/abc/db-tuning.md")])

    assert absent == []


def test_same_document_listed_once_when_several_chunks_match(capsys) -> None:
    """同一篇文档有多个片段命中时，文档名只应出现一次。

    曾经的实现漏了去重，输出会变成「catalog.md, catalog.md」——不影响判定结论，
    但报告可读性变差，也容易让人误以为有两篇同名文档。
    """
    payload = _payload({"question": "ERROR 1451 是什么原因导致的", "answer_span": ["1451", "外键引用"]})
    chunks = [
        _chunk("ERROR 1451 外键引用约束失败", "kb/31/a/catalog.md"),
        _chunk("1451 与外键引用相关的补充说明", "kb/31/a/catalog.md"),
    ]

    verify_index.check_answers(payload, chunks)

    output = capsys.readouterr().out
    assert output.count("catalog.md") == 1


def test_reliability_requires_every_signal() -> None:
    """四条信号缺一不可：稳定、无失败、无缺失文档、无缺失答案。

    任何一条不过关都必须判为「不可信」，否则报告会给出一个看似正常、实则失真的指标。
    """
    assert verify_index.is_reliable(True, [], [], []) is True
    assert verify_index.is_reliable(False, [], [], []) is False, "索引仍在变"
    assert verify_index.is_reliable(True, ["doc.md: FAILED"], [], []) is False, "有文档解析失败"
    assert verify_index.is_reliable(True, [], [{"question": "q"}], []) is False, "有答案不在库里"
    assert verify_index.is_reliable(True, [], [], ["release-process.md"]) is False, "有文档没入库"
