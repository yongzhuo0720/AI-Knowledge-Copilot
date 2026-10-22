"""结构化抽取的单元测试。

xlsx / pptx 的测试用例用标准库现场构造**真实的 OOXML 包**（ZIP + XML），
而不是造假数据喂给解析函数 —— 只有这样才能验证「共享字符串表按下标回填」
「工作表物理文件名由关系文件决定」这些真正会出错的地方。
"""

import zipfile
from io import BytesIO

import pytest

from app.document_blocks import DocumentBlock, extract_blocks

# --------------------------------------------------------------------------- #
# 构造测试用的 OOXML 包
# --------------------------------------------------------------------------- #

_MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
_DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
_PRESENTATION_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"


def _column_name(index: int) -> str:
    """0 起算的列号 → Excel 列名（0→A, 26→AA）。"""
    name = ""
    index += 1
    while index:
        index, remainder = divmod(index - 1, 26)
        name = chr(ord("A") + remainder) + name
    return name


def _build_xlsx(sheets: dict[str, list[list[str]]]) -> bytes:
    shared: list[str] = []
    shared_index: dict[str, int] = {}

    def _share(value: str) -> int:
        if value not in shared_index:
            shared_index[value] = len(shared)
            shared.append(value)
        return shared_index[value]

    sheet_files: list[tuple[str, str, str]] = []
    for order, (name, rows) in enumerate(sheets.items(), start=1):
        target = f"worksheets/sheet{order}.xml"
        body = []
        for row_index, row in enumerate(rows, start=1):
            cells = []
            for column_index, value in enumerate(row):
                reference = f"{_column_name(column_index)}{row_index}"
                if value and not value.lstrip("-").isdigit():
                    cells.append(f'<c r="{reference}" t="s"><v>{_share(value)}</v></c>')
                elif value:
                    cells.append(f'<c r="{reference}"><v>{value}</v></c>')
                # 空单元格直接不写，用来验证稀疏行的补齐
            body.append(f'<row r="{row_index}">{"".join(cells)}</row>')
        sheet_files.append((
            name,
            f"xl/{target}",
            f'<?xml version="1.0"?><worksheet xmlns="{_MAIN_NS}"><sheetData>{"".join(body)}</sheetData></worksheet>',
        ))

    workbook_sheets = "".join(
        f'<sheet name="{name}" sheetId="{order}" r:id="rId{order}"/>'
        for order, (name, _, _) in enumerate(sheet_files, start=1)
    )
    relationships = "".join(
        f'<Relationship Id="rId{order}" Type="{_REL_NS}/worksheet" Target="{target.removeprefix("xl/")}"/>'
        for order, (_, target, _) in enumerate(sheet_files, start=1)
    )

    payload = {
        "xl/workbook.xml": (
            f'<?xml version="1.0"?><workbook xmlns="{_MAIN_NS}" xmlns:r="{_REL_NS}">'
            f"<sheets>{workbook_sheets}</sheets></workbook>"
        ),
        "xl/_rels/workbook.xml.rels": (
            f'<?xml version="1.0"?><Relationships xmlns="{_PKG_REL_NS}">{relationships}</Relationships>'
        ),
        "xl/sharedStrings.xml": (
            f'<?xml version="1.0"?><sst xmlns="{_MAIN_NS}">'
            + "".join(f"<si><t>{value}</t></si>" for value in shared)
            + "</sst>"
        ),
    }
    for _, target, xml in sheet_files:
        payload[target] = xml

    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, xml in payload.items():
            archive.writestr(name, xml)
    return buffer.getvalue()


def _build_pptx(slides: list[list[str]]) -> bytes:
    payload: dict[str, str] = {}
    for order, paragraphs in enumerate(slides, start=1):
        runs = "".join(
            f'<a:p><a:r><a:t>{text}</a:t></a:r></a:p>'
            for text in paragraphs
        )
        payload[f"ppt/slides/slide{order}.xml"] = (
            f'<?xml version="1.0"?><p:sld xmlns:a="{_DRAWING_NS}" xmlns:p="{_PRESENTATION_NS}">'
            f"<p:cSld><p:spTree><p:sp><p:txBody>{runs}</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"
        )
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, xml in payload.items():
            archive.writestr(name, xml)
    return buffer.getvalue()


# --------------------------------------------------------------------------- #
# 类型路由
# --------------------------------------------------------------------------- #

def test_extension_wins_over_misleading_content_type() -> None:
    """浏览器常把 .csv 报成 application/vnd.ms-excel，扩展名必须优先。"""
    blocks = extract_blocks("型号,限流\nXZ-2500,200\n".encode(), "application/vnd.ms-excel", "spec.csv")

    assert blocks[0].kind == "row"
    assert "型号: XZ-2500" in blocks[0].text


def test_unsupported_type_raises() -> None:
    with pytest.raises(ValueError, match="unsupported document type"):
        extract_blocks(b"\x00\x01", "application/octet-stream", "blob.bin")


def test_legacy_binary_formats_raise_a_clear_error() -> None:
    """旧版二进制格式没有实现，要明确报错，不能静默产出错误切片。"""
    with pytest.raises(ValueError, match="convert to .xlsx"):
        extract_blocks(b"anything", "", "old.xls")
    with pytest.raises(ValueError, match="convert to .pptx"):
        extract_blocks(b"anything", "", "old.ppt")


# --------------------------------------------------------------------------- #
# 编码
# --------------------------------------------------------------------------- #

def test_decodes_gbk_content() -> None:
    """中文 Windows 导出的 CSV 常是 GBK，硬按 utf-8 解会得到乱码而不报错。"""
    blocks = extract_blocks("型号\nXZ-2500\n".encode("gbk"), "text/csv", "spec.csv")

    assert "XZ-2500" in blocks[0].text


def test_strips_utf8_bom() -> None:
    """BOM 不剥掉的话，首个标题会被当成 '\\ufeff# 标题' 而识别失败。"""
    blocks = extract_blocks("# 退款政策\n\n正文。".encode("utf-8-sig"), "text/markdown", "refund.md")

    assert blocks[0].heading_path == ("退款政策",), f"实际 {blocks[0].heading_path}"


# --------------------------------------------------------------------------- #
# Markdown
# --------------------------------------------------------------------------- #

def test_markdown_builds_nested_heading_path() -> None:
    markdown = (
        "# 退款政策\n\n总则。\n\n"
        "## 审批流程\n\n超过五万元需审批。\n\n"
        "### 加急\n\n加急由值班经理垫付。\n"
    )
    blocks = extract_blocks(markdown.encode(), "text/markdown", "refund.md")

    paths = [block.heading_path for block in blocks]
    assert paths == [
        ("退款政策",),
        ("退款政策", "审批流程"),
        ("退款政策", "审批流程", "加急"),
    ]


def test_markdown_heading_level_rolls_back() -> None:
    """``##`` 之后出现 ``#``，标题链必须回退，不能残留上一级的子标题。"""
    blocks = extract_blocks(
        "# 甲\n\n甲正文\n\n## 甲一\n\n甲一正文\n\n# 乙\n\n乙正文".encode(),
        "text/markdown",
        "a.md",
    )

    assert [block.heading_path for block in blocks] == [("甲",), ("甲", "甲一"), ("乙",)]


def test_markdown_headings_are_not_emitted_as_body() -> None:
    """标题只进标题链，不重复进正文 —— 否则 chunk 前缀和正文会出现重复内容。"""
    blocks = extract_blocks("# 退款政策\n\n正文。".encode(), "text/markdown", "a.md")

    assert len(blocks) == 1
    assert blocks[0].text == "正文。"
    assert "退款政策" not in blocks[0].text


def test_hash_inside_code_fence_is_not_a_heading() -> None:
    """代码块里的 # 是注释，不识别的话整篇文档的标题结构会被搅乱。"""
    markdown = "# 标题\n\n```python\n# 这是注释，不是标题\nprint(1)\n```\n\n正文。\n"
    blocks = extract_blocks(markdown.encode(), "text/markdown", "a.md")

    assert all(block.heading_path == ("标题",) for block in blocks)
    assert any("这是注释" in block.text for block in blocks)


def test_markdown_paragraphs_split_on_blank_lines() -> None:
    blocks = extract_blocks("# 标题\n\n第一段。\n\n第二段。".encode(), "text/markdown", "a.md")

    assert [block.text for block in blocks] == ["第一段。", "第二段。"]


# --------------------------------------------------------------------------- #
# CSV / 表格
# --------------------------------------------------------------------------- #

def test_csv_row_carries_column_labels() -> None:
    """一行 ``XZ-2500 | 200`` 单独看毫无意义，必须带上列名。"""
    blocks = extract_blocks("型号,默认限流\nXZ-2500,每秒两百次\n".encode(), "text/csv", "spec.csv")

    assert len(blocks) == 1
    assert blocks[0].text == "型号: XZ-2500 | 默认限流: 每秒两百次"
    assert blocks[0].kind == "row"
    assert blocks[0].heading_path == ("spec",)


def test_csv_skips_empty_cells_and_blank_rows() -> None:
    blocks = extract_blocks("型号,限流,备注\nXZ-2500,200,\n,\nXZ-3300,300,常用\n".encode(), "text/csv", "spec.csv")

    assert [block.text for block in blocks] == [
        "型号: XZ-2500 | 限流: 200",
        "型号: XZ-3300 | 限流: 300 | 备注: 常用",
    ]


def test_csv_header_only_becomes_content() -> None:
    """只有一行时它是内容而不是表头，不能整张表读成空。"""
    blocks = extract_blocks("型号,限流\n".encode(), "text/csv", "spec.csv")

    assert [block.text for block in blocks] == ["型号 | 限流"]


def test_csv_semicolon_delimiter_is_detected() -> None:
    blocks = extract_blocks("型号;限流\nXZ-2500;200\n".encode(), "text/csv", "spec.csv")

    assert blocks[0].text == "型号: XZ-2500 | 限流: 200"


# --------------------------------------------------------------------------- #
# XLSX（标准库解析 OOXML）
# --------------------------------------------------------------------------- #

def test_xlsx_uses_sheet_name_as_heading_path() -> None:
    payload = _build_xlsx({"型号表": [["型号", "限流"], ["XZ-2500", "200"]]})
    blocks = extract_blocks(payload, "", "catalog.xlsx")

    assert blocks[0].heading_path == ("型号表",)
    assert blocks[0].text == "型号: XZ-2500 | 限流: 200"


def test_xlsx_resolves_shared_strings_by_index() -> None:
    """共享字符串表靠下标对应，取错下标会得到别处的文字（或纯数字）。"""
    payload = _build_xlsx({"表": [["列名", "第二列"], ["第一个值", "第二个值"]]})
    blocks = extract_blocks(payload, "", "a.xlsx")

    assert blocks[0].text == "列名: 第一个值 | 第二列: 第二个值"


def test_xlsx_keeps_sheets_separate() -> None:
    payload = _build_xlsx({
        "甲表": [["列", "值"], ["甲", "1"]],
        "乙表": [["列", "值"], ["乙", "2"]],
    })
    blocks = extract_blocks(payload, "", "a.xlsx")

    assert [block.heading_path for block in blocks] == [("甲表",), ("乙表",)]


def test_xlsx_sparse_row_keeps_columns_aligned() -> None:
    """中间空单元格不补齐的话，后面的值会顶到前面的列名上。"""
    payload = _build_xlsx({"表": [["甲", "乙", "丙"], ["", "", "只有丙有值"]]})
    blocks = extract_blocks(payload, "", "a.xlsx")

    assert blocks[0].text == "丙: 只有丙有值", f"实际 {blocks[0].text}"


def test_xlsx_numeric_cells_are_plain_numbers() -> None:
    payload = _build_xlsx({"表": [["型号", "数量"], ["XZ-2500", "42"]]})
    blocks = extract_blocks(payload, "", "a.xlsx")

    assert "数量: 42" in blocks[0].text
    assert "数量: 42：" not in blocks[0].text


# --------------------------------------------------------------------------- #
# PPTX
# --------------------------------------------------------------------------- #

def test_pptx_orders_slides_numerically() -> None:
    """字典序会把 slide10 排到 slide2 前面，页码顺序必须按数字排。"""
    slides = [[f"标题{n}", f"正文{n}"] for n in range(1, 11)]
    blocks = extract_blocks(_build_pptx(slides), "", "deck.pptx")

    assert [block.heading_path[0] for block in blocks] == [f"第{n}页" for n in range(1, 11)]
    assert blocks[1].heading_path == ("第2页", "标题2")


def test_pptx_first_paragraph_is_the_title() -> None:
    blocks = extract_blocks(_build_pptx([["季度总结", "第一点", "第二点"]]), "", "deck.pptx")

    assert blocks[0].heading_path == ("第1页", "季度总结")
    assert blocks[0].text == "第一点\n第二点"
    assert "季度总结" not in blocks[0].text


def test_pptx_title_only_slide_stays_as_heading_only_block() -> None:
    blocks = extract_blocks(_build_pptx([["封面标题"]]), "", "deck.pptx")

    assert blocks[0].text == "封面标题"
    assert blocks[0].heading_path == ("第1页",)


def test_pptx_empty_slide_produces_nothing() -> None:
    blocks = extract_blocks(_build_pptx([[], ["有内容"]]), "", "deck.pptx")

    assert len(blocks) == 1
    assert blocks[0].heading_path[0] == "第2页"


# --------------------------------------------------------------------------- #
# HTML
# --------------------------------------------------------------------------- #

def test_html_headings_build_heading_path() -> None:
    html = "<h1>退款政策</h1><p>总则。</p><h2>审批流程</h2><p>需财务总监审批。</p>"
    blocks = extract_blocks(html.encode(), "text/html", "policy.html")

    assert [block.heading_path for block in blocks] == [
        ("退款政策",),
        ("退款政策", "审批流程"),
    ]
    assert blocks[0].text == "总则。"


def test_html_skips_script_and_style() -> None:
    """不跳过 script/style 的话，内联 JS 会被当正文索引进去，检索结果全是代码噪声。"""
    html = "<style>body{color:red}</style><script>var x=1;</script><p>真正的正文</p>"
    blocks = extract_blocks(html.encode(), "text/html", "a.html")

    assert [block.text for block in blocks] == ["真正的正文"]


def test_html_table_rows_are_joined_with_separator() -> None:
    html = "<table><tr><th>型号</th><th>限流</th></tr><tr><td>XZ-2500</td><td>200</td></tr></table>"
    blocks = extract_blocks(html.encode(), "text/html", "a.html")

    assert [block.text for block in blocks] == ["型号 | 限流", "XZ-2500 | 200"]
    assert all(block.kind == "row" for block in blocks)


def test_html_cell_text_with_inline_markup_stays_in_its_row() -> None:
    """回归保护：单元格内部的 ``<strong>`` 之类行内标签不该把文字赶出单元格。

    修复前的毛病是单元格文字被收进外层缓冲区，于是整行表格粘成一串
    ``型号 限流 XZ-2500 200``，列与列的分界彻底丢失。
    """
    html = (
        "<table>"
        "<tr><td><strong>型号</strong></td><td>限流</td></tr>"
        "<tr><td>XZ-2500</td><td><em>200</em></td></tr>"
        "</table>"
    )

    blocks = extract_blocks(html.encode(), "text/html", "a.html")

    assert [block.text for block in blocks] == ["型号 | 限流", "XZ-2500 | 200"]


def test_html_entities_are_unescaped() -> None:
    blocks = extract_blocks("<p>温度 &gt; 40&amp;&lt; 60</p>".encode(), "text/html", "a.html")

    assert blocks[0].text == "温度 > 40&< 60"


# --------------------------------------------------------------------------- #
# 纯文本
# --------------------------------------------------------------------------- #

def test_plain_text_splits_on_blank_lines() -> None:
    blocks = extract_blocks("第一段\n\n第二段\n\n第三段".encode(), "text/plain", "a.txt")

    assert [block.text for block in blocks] == ["第一段", "第二段", "第三段"]


def test_plain_text_keeps_heading_path_empty() -> None:
    blocks = extract_blocks("纯文本内容".encode(), "text/plain", "a.txt")

    assert blocks[0] == DocumentBlock(text="纯文本内容", kind="text", heading_path=())


def test_blank_input_produces_no_blocks() -> None:
    assert extract_blocks(b"\n\n   \n", "text/plain", "a.txt") == []
    assert extract_blocks("   \n\t\n".encode(), "text/plain", "a.txt") == []


def test_docx_preserves_tables_between_paragraphs() -> None:
    docx = pytest.importorskip("docx")
    document = docx.Document()
    document.add_heading("设备配置", level=1)
    document.add_paragraph("表格前说明。")
    table = document.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "型号"
    table.cell(0, 1).text = "限流"
    table.cell(1, 0).text = "XZ-2500"
    table.cell(1, 1).text = "200"
    document.add_paragraph("表格后说明。")
    buffer = BytesIO()
    document.save(buffer)

    blocks = extract_blocks(buffer.getvalue(), "", "guide.docx")

    assert [block.text for block in blocks] == [
        "表格前说明。", "型号: XZ-2500 | 限流: 200", "表格后说明。",
    ]
    assert [block.kind for block in blocks] == ["text", "row", "text"]
    assert all(block.heading_path == ("设备配置",) for block in blocks)


def test_docx_table_only_document_has_extractable_text() -> None:
    docx = pytest.importorskip("docx")
    document = docx.Document()
    document.add_table(rows=1, cols=1).cell(0, 0).text = "仅在表格中的内容"
    buffer = BytesIO()
    document.save(buffer)

    blocks = extract_blocks(buffer.getvalue(), "", "table.docx")

    assert [block.text for block in blocks] == ["仅在表格中的内容"]
