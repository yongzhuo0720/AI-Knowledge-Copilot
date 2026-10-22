"""查询改写的单元测试。

这个模块的全部价值在于「**失败即等于没这个功能**」，所以测试的重点不是
「改写得好不好」，而是**每一条失败路径都真的退化成了空列表**：

- 没配模型 key → 不调用模型，直接返回空；
- 模型超时 / 网络异常 / 返回结构不对 → 吞掉异常，返回空，并且**记一条 warning**；
- 模型返回脏输出（编号前缀、引号、空行、重复、过长、原样抄回问题）→ 逐条过滤掉。

只要有一条失败路径漏成了抛异常，检索就会整体不可用 —— 把「多召回一点」的增强
变成「问答彻底挂掉」的故障。所以这里对每条路径都单独钉一遍。

全部 monkeypatch 掉 ``_call_chat``，不碰网络。
"""

import pytest

from app import query_rewrite
from app.settings import settings


@pytest.fixture(autouse=True)
def _with_chat_key(monkeypatch):
    """默认给一个 key，让用例能走到「真的去改写」那一步。

    个别用例（没 key 的场景）自己在用例内覆盖它。
    """
    monkeypatch.setattr(settings, "chat_api_key", "test-key")
    monkeypatch.setattr(settings, "query_rewrite_count", 3)


# --------------------------------------------------------------------------- #
# 失败路径：一律返回空列表
# --------------------------------------------------------------------------- #

def test_returns_empty_without_calling_model_when_no_key(monkeypatch) -> None:
    monkeypatch.setattr(settings, "chat_api_key", "")
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: pytest.fail("没配 key 就不该发请求"))

    assert query_rewrite.rewrite_query("怎么提高导出速度") == []


def test_returns_empty_when_model_call_fails(monkeypatch) -> None:
    """最关键的一条：模型炸了不能把检索一起带下水。"""

    def explode(prompt: str) -> str:
        raise TimeoutError("read timeout")

    monkeypatch.setattr(query_rewrite, "_call_chat", explode)

    assert query_rewrite.rewrite_query("怎么提高导出速度") == []


def test_logs_a_warning_when_the_model_call_fails(monkeypatch, caplog) -> None:
    """配了 key 却失败属于异常情况，必须留下日志。

    否则线上表现只是「检索好像没变好」，没有任何信号可查。
    """

    def explode(prompt: str) -> str:
        raise RuntimeError("boom")

    monkeypatch.setattr(query_rewrite, "_call_chat", explode)

    with caplog.at_level("WARNING"):
        query_rewrite.rewrite_query("怎么提高导出速度")

    assert "query rewrite failed" in caplog.text


def test_returns_empty_for_blank_question(monkeypatch) -> None:
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: pytest.fail("空问题不该发请求"))

    assert query_rewrite.rewrite_query("   ") == []
    assert query_rewrite.rewrite_query("") == []


def test_returns_empty_when_count_is_not_positive(monkeypatch) -> None:
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: pytest.fail("count<=0 不该发请求"))

    assert query_rewrite.rewrite_query("怎么提高导出速度", count=0) == []
    assert query_rewrite.rewrite_query("怎么提高导出速度", count=-1) == []


def test_returns_empty_when_model_outputs_nothing_usable(monkeypatch) -> None:
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: "\n\n   \n")

    assert query_rewrite.rewrite_query("怎么提高导出速度") == []


# --------------------------------------------------------------------------- #
# 解析：脏输出逐条过滤
# --------------------------------------------------------------------------- #

def test_parses_plain_one_per_line(monkeypatch) -> None:
    monkeypatch.setattr(
        query_rewrite, "_call_chat",
        lambda prompt: "如何优化报表导出性能\n导出慢的常见原因\n报表生成速度提升办法",
    )

    assert query_rewrite.rewrite_query("报表导出特别慢怎么办") == [
        "如何优化报表导出性能",
        "导出慢的常见原因",
        "报表生成速度提升办法",
    ]


def test_strips_numbering_and_bullet_prefixes(monkeypatch) -> None:
    """模型很爱加编号，即使提示词明确说了不要。解析侧负责「一定」。"""
    monkeypatch.setattr(
        query_rewrite, "_call_chat",
        lambda prompt: "1. 如何优化报表导出性能\n- 导出慢的常见原因\n2）报表生成速度提升办法\n* 第四个说法",
    )

    rewrites = query_rewrite.rewrite_query("报表导出特别慢怎么办", count=4)

    assert rewrites == [
        "如何优化报表导出性能",
        "导出慢的常见原因",
        "报表生成速度提升办法",
        "第四个说法",
    ]


def test_strips_wrapping_quotes(monkeypatch) -> None:
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: '“如何优化报表导出性能”\n「导出慢的常见原因」')

    assert query_rewrite.rewrite_query("报表导出特别慢怎么办") == [
        "如何优化报表导出性能",
        "导出慢的常见原因",
    ]


def test_drops_the_original_question_and_duplicates(monkeypatch) -> None:
    """原样抄回问题和重复行都要丢掉。

    它们不是「无害的多余项」：RRF 里重复出现等于给同一个片段投两票，
    会凭空放大某一路的权重 —— 多查询融合最怕的就是这种隐性重复。
    """
    monkeypatch.setattr(
        query_rewrite, "_call_chat",
        lambda prompt: "\n".join([
            "报表导出特别慢怎么办",          # 原样抄回
            "  报表导出特别慢怎么办  ",      # 加空格后还是同一个
            "REPORT 导出慢怎么办",           # 这条留下
            "report 导出慢怎么办",           # 上一条的小写形式 → 判为重复，丢掉
            "如何优化报表导出性能",
            "如何优化报表导出性能",          # 完全重复
            "导出性能优化方案",
        ]),
    )

    rewrites = query_rewrite.rewrite_query("报表导出特别慢怎么办", count=5)

    assert rewrites == ["REPORT 导出慢怎么办", "如何优化报表导出性能", "导出性能优化方案"]


def test_drops_overlong_lines(monkeypatch) -> None:
    """过长的行基本意味着模型开始解释或直接答题了，不该塞进检索。"""
    long_line = "这是一段明显过长的内容" * 40
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: f"{long_line}\n导出性能优化方案")

    assert query_rewrite.rewrite_query("报表导出特别慢怎么办") == ["导出性能优化方案"]


def test_caps_the_number_of_rewrites(monkeypatch) -> None:
    monkeypatch.setattr(
        query_rewrite, "_call_chat",
        lambda prompt: "\n".join(f"改写说法{i}" for i in range(1, 11)),
    )

    assert len(query_rewrite.rewrite_query("报表导出特别慢怎么办", count=3)) == 3


def test_does_not_duplicate_a_rewrite_that_matches_the_question_only_by_case(monkeypatch) -> None:
    monkeypatch.setattr(query_rewrite, "_call_chat", lambda prompt: "OPTIMIZE EXPORT")

    # 原问题就是同一句话的另一副大小写 → 应当被认成重复
    assert query_rewrite.rewrite_query("optimize export") == []


# --------------------------------------------------------------------------- #
# 提示词
# --------------------------------------------------------------------------- #

def test_prompt_asks_for_the_requested_count_and_pins_identifiers() -> None:
    prompt = query_rewrite._build_prompt("ERROR 1451 是什么原因", 4)

    assert "4 条" in prompt
    assert "ERROR 1451 是什么原因" in prompt
    # 「编号原样保留」是这个提示词里最关键的一条约束：
    # 一旦模型把 ERROR 1451 改写成「外键约束错误」，BM25 那一路就全废了。
    assert "编号、型号、版本号、错误码必须原样保留" in prompt
