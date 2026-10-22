"""结构感知切片的单元测试。

重点验证三件事：
1. 切片的边界只落在结构边界上（段落 / 表格行 / 小节），不会切断内容；
2. 每片都带上所属小标题前缀，且标题变化必然是硬边界；
3. 只有在单块超长时才走字符滑窗，且不产生孤儿片段、不丢失内容。
"""

import pytest

from app.chunking import chunk_blocks, chunk_document, render_prefix, windows
from app.document_blocks import DocumentBlock


def _block(text: str, path: tuple[str, ...] = ()) -> DocumentBlock:
    return DocumentBlock(text=text, kind="text", heading_path=path)


# --------------------------------------------------------------------------- #
# 标题前缀
# --------------------------------------------------------------------------- #

def test_render_prefix_of_empty_path_is_empty() -> None:
    assert render_prefix(()) == ""


def test_render_prefix_joins_levels_with_arrow() -> None:
    assert render_prefix(("退款政策", "审批流程")) == "退款政策 > 审批流程"


def test_render_prefix_drops_blank_titles() -> None:
    assert render_prefix(("甲", "", "乙")) == "甲 > 乙"


# --------------------------------------------------------------------------- #
# 字符滑窗（仅用于单块超长）
# --------------------------------------------------------------------------- #

def test_windows_returns_text_as_is_when_within_size() -> None:
    assert windows("abc", 10, 2) == ["abc"]


def test_windows_does_not_split_when_barely_over_size() -> None:
    """801 字按 800 切会得到两片重叠 799 字的内容，等于把同一段索引两遍。"""
    text = "a" * 21

    assert windows(text, 20, 4) == [text], "只超出不到一个 step 时不该切"


def test_windows_produce_uniform_pieces_with_overlap() -> None:
    text = "".join(chr(ord("a") + index % 26) for index in range(100))

    pieces = windows(text, 20, 4)

    assert len(pieces) == 6
    assert all(len(piece) == 20 for piece in pieces), "每片长度必须恰好等于 size"
    assert pieces[0] == text[:20]
    assert pieces[-1] == text[-20:], "最后一片必须对齐到文本末尾"
    for earlier, later in zip(pieces, pieces[1:]):
        assert earlier[-4:] == later[:4], "相邻片必须重叠给定长度"


def test_windows_never_lose_content() -> None:
    """覆盖性是硬要求：滑窗是唯一可能丢内容的路径。

    文本用**互不相同的字符**构造，这样 ``text.index(piece)`` 能唯一定位每片的起点
    —— 用重复数字构造的话，同样的子串会出现多次，定位就是错的。
    """
    text = "".join(chr(0x4E00 + index) for index in range(300))

    pieces = windows(text, 64, 16)

    covered: set[int] = set()
    for piece in pieces:
        start = text.index(piece)
        covered.update(range(start, start + len(piece)))
    assert covered == set(range(len(text)))


def test_windows_rejects_non_positive_size() -> None:
    with pytest.raises(ValueError, match="size must be positive"):
        windows("abc", 0, 0)


# --------------------------------------------------------------------------- #
# 打包规则
# --------------------------------------------------------------------------- #

def test_empty_blocks_produce_no_chunks() -> None:
    assert chunk_blocks([], 100, 10) == []


def test_blank_blocks_are_skipped() -> None:
    assert chunk_blocks([_block("   "), _block("\n")], 100, 10) == []


def test_single_block_becomes_one_chunk() -> None:
    assert chunk_blocks([_block("正文。")], 100, 10) == ["正文。"]


def test_chunk_gets_heading_prefix() -> None:
    chunks = chunk_blocks([_block("需财务总监审批。", ("退款政策", "审批流程"))], 100, 10)

    assert chunks == ["退款政策 > 审批流程\n\n需财务总监审批。"]


def test_heading_change_is_a_hard_boundary() -> None:
    """不同小节的段落绝不能混进同一片 —— 否则标题前缀就没有意义了。"""
    chunks = chunk_blocks([
        _block("甲正文", ("甲",)),
        _block("乙正文", ("乙",)),
    ], 800, 120)

    assert chunks == ["甲\n\n甲正文", "乙\n\n乙正文"]


def test_paragraphs_are_packed_up_to_size() -> None:
    blocks = [_block("0123456789") for _ in range(4)]

    chunks = chunk_blocks(blocks, 30, 10)

    assert chunks == ["0123456789\n0123456789", "0123456789\n0123456789"]


def test_no_chunk_exceeds_size_when_blocks_fit() -> None:
    """所有块都不超 size 时，成片正文必须落在预算内。"""
    blocks = [_block("x" * 7, ("节",)) for _ in range(40)]

    chunks = chunk_blocks(blocks, 50, 10)

    assert all(len(chunk.removeprefix("节\n\n")) <= 50 for chunk in chunks)


def test_oversized_block_is_windowed_with_prefix() -> None:
    chunks = chunk_blocks([_block("a" * 100, ("甲",))], 20, 4)

    assert len(chunks) == 6
    assert all(chunk.startswith("甲\n\n") for chunk in chunks)
    assert all(len(chunk.removeprefix("甲\n\n")) == 20 for chunk in chunks)


def test_oversized_block_flushes_pending_buffer_first() -> None:
    """超长块前面已攒了内容时，必须先落盘，不能把两者混在一片里。"""
    chunks = chunk_blocks([_block("短句。"), _block("b" * 100)], 20, 4)

    assert chunks[0] == "短句。"
    assert all("短句。" not in chunk for chunk in chunks[1:])


def test_blocks_from_different_sections_never_share_a_chunk_even_when_small() -> None:
    blocks = [_block(f"第{index}段", (f"节{index}",)) for index in range(5)]

    chunks = chunk_blocks(blocks, 800, 120)

    assert len(chunks) == 5, "每节都该独立成片，不能因为总量小就合并"


def test_chunk_blocks_rejects_non_positive_size() -> None:
    with pytest.raises(ValueError, match="size must be positive"):
        chunk_blocks([_block("x")], 0, 0)


# --------------------------------------------------------------------------- #
# 端到端
# --------------------------------------------------------------------------- #

def test_chunk_document_end_to_end_on_markdown() -> None:
    markdown = (
        "# 退款政策\n\n总则。\n\n"
        "## 审批流程\n\n超过五万元需审批。\n"
    )

    chunks = chunk_document(markdown.encode(), "text/markdown", "refund.md")

    assert chunks == [
        "退款政策\n\n总则。",
        "退款政策 > 审批流程\n\n超过五万元需审批。",
    ]


def test_chunk_document_keeps_table_rows_intact() -> None:
    """表格行的关键性质：每一行都完整落进某一片，不会被从中间切断。"""
    csv_text = "型号,限流\nXZ-2500,200\nXZ-3300,300\n"

    chunks = chunk_document(csv_text.encode(), "text/csv", "spec.csv")

    assert all(chunk.startswith("spec\n\n") for chunk in chunks)
    lines = [
        line
        for chunk in chunks
        for line in chunk.removeprefix("spec\n\n").splitlines()
    ]
    assert lines == [
        "型号: XZ-2500 | 限流: 200",
        "型号: XZ-3300 | 限流: 300",
    ]


def test_chunk_document_raises_on_unsupported_type() -> None:
    with pytest.raises(ValueError, match="unsupported document type"):
        chunk_document(b"\x00", "application/octet-stream", "blob.bin")
