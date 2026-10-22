"""文档 → 结构化块（DocumentBlock）。

## 为什么抽取阶段就必须保留结构

原来的做法是「先把文档抽成一段纯文本，再按 800 字符滑窗切」。这条路走不通的根因是：
**一旦结构信息被丢掉，后面无论怎么切都只能在无结构的一长串字符上猜边界**。

具体会坏在哪：

- 一个完整段落被从中间切断，检索到的片段是「半句话」；
- 表格的一行被拦腰截断，单元格数值和它的列名分了家，模型看不懂这列是什么；
- 小节标题与它下面的正文被分到不同片段，片段丢掉主题。

所以这里把抽取的产物从 ``str`` 改成 ``list[DocumentBlock]``：每个块自带
「我属于哪个小节」。切片阶段因此可以只在块的边界上切，并且给每段补上标题前缀。

## 为什么不引入第三方解析库

xlsx / pptx 本质上都是 ZIP + XML。本项目需要的只是「取出单元格文本」和
「取出幻灯片文本」，用标准库 ``zipfile`` + ``xml.etree`` 就够了。

装 openpyxl / python-pptx 会带来两个真实成本：镜像构建依赖 PyPI 可用性
（本机实测不稳定），以及额外的依赖升级兼容风险。少一个依赖就少一处风险。

代价是**不支持旧版二进制格式**（.xls / .ppt）——那需要完全不同的解析器。
遇到时明确报错，而不是静默产出错误的切片。
"""

import csv
import re
import zipfile
from dataclasses import dataclass
from html.parser import HTMLParser
from io import BytesIO, StringIO
from pathlib import Path
from xml.etree import ElementTree

# --------------------------------------------------------------------------- #
# 数据模型
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class DocumentBlock:
    """一个结构完整的文本块 —— 切片只能落在块与块之间。

    :param text: 块的正文
    :param kind: ``text`` 普通段落 / ``row`` 表格行 / ``page`` 页面
    :param heading_path: 所属标题链，例如 ``("退款政策", "审批流程")``。
        切片时会被渲染成前缀加在 chunk 开头，这是「保留上下文」的关键。
    """

    text: str
    kind: str = "text"
    heading_path: tuple[str, ...] = ()


# --------------------------------------------------------------------------- #
# 通用工具
# --------------------------------------------------------------------------- #

_ATX_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
_FENCE = re.compile(r"^\s*(```|~~~)")
_CELL_REFERENCE = re.compile(r"^([A-Za-z]+)(\d+)$")
_DOCX_HEADING = re.compile(r"(?:heading|标题)\s*(\d)", re.IGNORECASE)
_SLIDE_FILE = re.compile(r"^ppt/slides/slide(\d+)\.xml$")


def _decode(content: bytes) -> str:
    """按顺序尝试常见编码。

    中文环境里 CSV / TXT 经常是 GBK 系编码，直接 ``utf-8`` 解码会得到一堆替换字符
    而**不报错**，索引进去的就是乱码——比抛异常更危险。所以这里逐个尝试，
    并且先试 ``utf-8-sig`` 把 BOM 剥掉（本项目已被 BOM 坑过一次）。
    """
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return content.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return content.decode("utf-8", errors="replace")


def _local(tag: object) -> str:
    """去掉 XML 命名空间前缀，只看标签/属性名本身。

    OOXML 里标签都带长命名空间（``{http://...}si``），写死命名空间会在
    Office 版本变化时脆断，按局部名匹配更稳。
    """
    return str(tag).rsplit("}", 1)[-1]


def _elements(root: ElementTree.Element, name: str):
    return (node for node in root.iter() if _local(node.tag) == name)


def _text_of(element: ElementTree.Element) -> str:
    return "".join(node.text or "" for node in element.iter() if _local(node.tag) == "t")


# --------------------------------------------------------------------------- #
# 正文类：Markdown / 纯文本 / PDF / DOCX
# --------------------------------------------------------------------------- #


def prose_blocks(text: str, markdown: bool = False) -> list[DocumentBlock]:
    """把正文按「标题 + 空行分段」切成块。

    ``markdown=True`` 时识别 ATX 标题（``#`` ~ ``######``）并维护标题链。
    代码围栏内的 ``#`` 不当作标题 —— 否则一段带注释的 Python 代码会把整篇文档的
    标题结构搅乱，这是真实会踩的坑。

    标题本身**不作为正文块输出**：它的文字已经进了 ``heading_path``，会被渲染成
    chunk 前缀。再输出一遍就是重复内容，白白占用预算。
    """
    blocks: list[DocumentBlock] = []
    path: tuple[str, ...] = ()
    paragraph: list[str] = []
    in_fence = False

    def flush() -> None:
        nonlocal paragraph
        if paragraph:
            joined = "\n".join(paragraph).strip()
            if joined:
                blocks.append(DocumentBlock(text=joined, kind="text", heading_path=path))
            paragraph = []

    for raw_line in text.splitlines():
        line = raw_line.rstrip()
        if _FENCE.match(line):
            in_fence = not in_fence
            paragraph.append(line)
            continue
        if not in_fence and markdown:
            match = _ATX_HEADING.match(line)
            if match:
                flush()
                level = len(match.group(1))
                # 层级回退：## 小节出现时，它下面的 ### 标题链必须被替换掉
                path = path[: level - 1] + (match.group(2).strip(),)
                continue
        if not line.strip():
            flush()
            continue
        paragraph.append(line)
    flush()
    return blocks


def _pdf_blocks(content: bytes) -> list[DocumentBlock]:
    """PDF 按页成块 —— PDF 没有可靠的标题结构，页码是最实在的边界。"""
    from pypdf import PdfReader

    blocks: list[DocumentBlock] = []
    for number, page in enumerate(PdfReader(BytesIO(content)).pages, start=1):
        text = (page.extract_text() or "").strip()
        if text:
            blocks.append(DocumentBlock(text=text, kind="page", heading_path=(f"第{number}页",)))
    return blocks


def _docx_blocks(content: bytes) -> list[DocumentBlock]:
    """DOCX 按正文顺序成块：段落保留标题层级，表格按行渲染。

    不能用 ``document.paragraphs``：它**只返回段落，表格被整段跳过**，
    于是纯表格文档抽出来是空的（上传成功、解析“成功”，但检索永远没有内容）。
    改用 ``iter_inner_content()`` 才能拿到段落与表格交错的真实顺序，
    表格也就能继承它所在小节的标题链。
    """
    from docx import Document
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    blocks: list[DocumentBlock] = []
    path: tuple[str, ...] = ()
    for item in Document(BytesIO(content)).iter_inner_content():
        if isinstance(item, Paragraph):
            text = (item.text or "").strip()
            if not text:
                continue
            style = (item.style.name if item.style is not None else "") or ""
            match = _DOCX_HEADING.search(style)
            if match:
                level = int(match.group(1))
                path = path[: level - 1] + (text,)
                continue
            blocks.append(DocumentBlock(text=text, kind="text", heading_path=path))
        elif isinstance(item, Table):
            rows = [[cell.text for cell in row.cells] for row in item.rows]
            blocks.extend(_rows_to_blocks(rows, path))
    return blocks


# --------------------------------------------------------------------------- #
# 表格类：CSV / XLSX
# --------------------------------------------------------------------------- #

# 表格行的渲染格式。把列名写在值前面，是「表格能不能被检索到」的关键：
# 一行数据 ``XZ-2500 | 200`` 单独看毫无意义，写成 ``型号: XZ-2500 | 默认限流: 200``
# 之后，无论是人看、BM25 匹配还是向量编码，都能对得上。
_COLUMN_SEPARATOR = " | "
_UNNAMED_COLUMN = "第{}列"


def _rows_to_blocks(rows: list[list[object]], heading_path: tuple[str, ...]) -> list[DocumentBlock]:
    """把二维表格转成块 —— 首行当表头，之后每行一个块，且每行自带表头。"""
    cleaned = [
        [str(cell).strip() if cell is not None else "" for cell in row]
        for row in rows
    ]
    cleaned = [row for row in cleaned if any(row)]
    if not cleaned:
        return []

    header, *body = cleaned
    if not body:
        # 只有一行：那它就是内容而不是表头
        return [DocumentBlock(text=_COLUMN_SEPARATOR.join(header), kind="row", heading_path=heading_path)]

    blocks: list[DocumentBlock] = []
    for row in body:
        pairs = []
        for index, value in enumerate(row):
            if not value:
                continue
            label = header[index] if index < len(header) and header[index] else _UNNAMED_COLUMN.format(index + 1)
            pairs.append(f"{label}: {value}")
        if pairs:
            blocks.append(DocumentBlock(
                text=_COLUMN_SEPARATOR.join(pairs),
                kind="row",
                heading_path=heading_path,
            ))
    return blocks


def _csv_rows(text: str) -> list[list[str]]:
    sample = text[:4096]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
    except csv.Error:
        dialect = csv.excel
    return [row for row in csv.reader(StringIO(text), dialect)]


def _xlsx_sheets(content: bytes) -> list[tuple[str, list[list[str]]]]:
    """用标准库读取 xlsx 的每个工作表。

    一个 xlsx 就是一个 ZIP：单元格文本集中在 ``xl/sharedStrings.xml``（共享字符串表），
    工作表数据在 ``xl/worksheets/sheetN.xml``，二者靠**下标**对应 —— 单元格写成
    ``<c r="B2" t="s"><v>7</v></c>`` 表示「B2 的值是共享字符串表第 7 项」。
    所以必须先读共享字符串表，再逐格回填。
    """
    with zipfile.ZipFile(BytesIO(content)) as archive:
        shared = _xlsx_shared_strings(archive)
        sheets: list[tuple[str, list[list[str]]]] = []
        for name, target in _xlsx_sheet_targets(archive):
            try:
                payload = archive.read(target)
            except KeyError:
                continue
            sheets.append((name, _xlsx_rows(payload, shared)))
        return sheets


def _xlsx_shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    except ElementTree.ParseError:
        return []
    return [_text_of(element) for element in _elements(root, "si")]


def _xlsx_sheet_targets(archive: zipfile.ZipFile) -> list[tuple[str, str]]:
    """读工作表名 → 实际文件路径的映射。

    不能假设「第 N 个 sheet 就是 sheetN.xml」：工作表的物理文件名由关系文件
    决定，重排或删除工作表后顺序会错位，那样取到的就是别的工作表的数据。
    """
    try:
        workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
        relationships = ElementTree.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    except (KeyError, ElementTree.ParseError):
        return []

    targets: dict[str, str] = {}
    for node in _elements(relationships, "Relationship"):
        identifier = node.get("Id")
        target = node.get("Target") or ""
        if not identifier or not target:
            continue
        target = target.lstrip("/")
        if not target.startswith("xl/"):
            target = "xl/" + target.removeprefix("./")
        targets[identifier] = target

    sheets: list[tuple[str, str]] = []
    for node in _elements(workbook, "sheet"):
        name = node.get("name") or "Sheet"
        relation = next((value for key, value in node.attrib.items() if _local(key) == "id"), None)
        target = targets.get(relation or "")
        if target:
            sheets.append((name, target))
    return sheets


def _column_index(reference: str) -> int:
    """把 ``AB12`` 这类单元格引用换算成 0 起算的列号。

    Excel 的列名是 26 进制但**没有 0**（A=1 … Z=26, AA=27），所以不能直接当进制转。
    """
    match = _CELL_REFERENCE.match(reference or "")
    if not match:
        return 0
    index = 0
    for char in match.group(1).upper():
        index = index * 26 + (ord(char) - ord("A") + 1)
    return index - 1


def _xlsx_cell_text(cell: ElementTree.Element, shared: list[str]) -> str:
    """取单元格的字符串值。

    ``t`` 属性决定 ``<v>`` 里的数字怎么解释：``s`` 是共享字符串表下标，
    ``b`` 是布尔，缺省是数值。搞错这一点的典型症状是整张表读出来全是「7」「12」
    这种下标数字，而真正的文字在共享字符串表里躺着没人取。
    """
    kind = cell.get("t") or "n"
    if kind == "inlineStr":
        return _text_of(cell)

    value = next(iter(_elements(cell, "v")), None)
    raw = (value.text or "").strip() if value is not None else ""
    if not raw:
        return ""
    if kind == "s":
        try:
            return shared[int(raw)]
        except (ValueError, IndexError):
            return ""
    if kind == "b":
        return "是" if raw == "1" else "否"
    return raw


def _xlsx_rows(payload: bytes, shared: list[str]) -> list[list[str]]:
    try:
        root = ElementTree.fromstring(payload)
    except ElementTree.ParseError:
        return []
    rows: list[list[str]] = []
    for row in _elements(root, "row"):
        cells: dict[int, str] = {}
        for cell in row:
            if _local(cell.tag) != "c":
                continue
            cells[_column_index(cell.get("r") or "")] = _xlsx_cell_text(cell, shared)
        if cells:
            width = max(cells) + 1
            # 稀疏行（中间有空单元格）要补齐，否则列名和值会错位
            rows.append([cells.get(index, "") for index in range(width)])
    return rows


def _xlsx_blocks(content: bytes) -> list[DocumentBlock]:
    """一个工作表一条标题链 —— 这样切片天然不会把两个表的数据混在一起。"""
    blocks: list[DocumentBlock] = []
    for name, rows in _xlsx_sheets(content):
        blocks.extend(_rows_to_blocks(rows, (name,)))
    return blocks


# --------------------------------------------------------------------------- #
# 幻灯片：PPTX
# --------------------------------------------------------------------------- #


def _pptx_blocks(content: bytes) -> list[DocumentBlock]:
    """PPTX 按页成块。每页的首个非空段落视为标题，进入标题链。"""
    with zipfile.ZipFile(BytesIO(content)) as archive:
        names = [name for name in archive.namelist() if _SLIDE_FILE.match(name)]
        # 按文件名里的数字排序：字典序会把 slide10 排到 slide2 前面
        names.sort(key=lambda name: int(_SLIDE_FILE.match(name).group(1)))  # type: ignore[union-attr]
        blocks: list[DocumentBlock] = []
        for order, name in enumerate(names, start=1):
            try:
                root = ElementTree.fromstring(archive.read(name))
            except ElementTree.ParseError:
                continue
            paragraphs = []
            for element in _elements(root, "p"):
                text = _text_of(element).strip()
                if text:
                    paragraphs.append(text)
            if not paragraphs:
                continue
            page = f"第{order}页"
            title, body = paragraphs[0], paragraphs[1:]
            heading_path = (page, title) if title else (page,)
            if not body:
                blocks.append(DocumentBlock(text=title, kind="page", heading_path=(page,)))
                continue
            blocks.append(DocumentBlock(text="\n".join(body), kind="page", heading_path=heading_path))
        return blocks


# --------------------------------------------------------------------------- #
# 网页：HTML
# --------------------------------------------------------------------------- #


class _HtmlBlockParser(HTMLParser):
    """按块级标签切分正文的 HTML 解析器。

    只依赖标准库 ``html.parser``。跳过 ``script`` / ``style`` / ``head`` ——
    否则内联 JavaScript 会被当成正文索引进去，检索结果里会出现一堆代码噪声。
    """

    _SKIP = frozenset({"script", "style", "noscript", "template", "head", "svg"})
    _ROW = frozenset({"tr"})
    _CELL = frozenset({"td", "th"})
    _BLOCK = frozenset({
        "p", "div", "li", "section", "article", "aside", "header", "footer",
        "blockquote", "pre", "dd", "dt", "figcaption", "main", "nav",
        "table", "thead", "tbody", "tfoot", "caption", "form",
    })

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[DocumentBlock] = []
        self._path: tuple[str, ...] = ()
        self._buffer: list[str] = []
        self._row: list[str] = []
        self._cell: list[str] = []
        self._in_cell = False
        self._skip_depth = 0

    # -- 内部 ---------------------------------------------------------------- #

    def _flush_text(self) -> None:
        if self._buffer:
            joined = re.sub(r"\s+", " ", " ".join(self._buffer)).strip()
            if joined:
                self.blocks.append(DocumentBlock(text=joined, kind="text", heading_path=self._path))
            self._buffer = []

    def _flush_row(self) -> None:
        if self._cell:
            # 兜底：单元格没闭合时也要把已收到的文字算进去
            self._row.append(re.sub(r"\s+", " ", " ".join(self._cell)).strip())
            self._cell = []
        self._in_cell = False
        row = [value for value in self._row if value]
        self._row = []
        if row:
            self.blocks.append(DocumentBlock(
                text=_COLUMN_SEPARATOR.join(row),
                kind="row",
                heading_path=self._path,
            ))

    # -- HTMLParser 钩子 ------------------------------------------------------ #

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in self._SKIP:
            self._skip_depth += 1
            return
        if self._skip_depth:
            return
        if tag in self._CELL:
            # 关键：进入单元格后，handle_data 必须把文字收进 _cell，
            # 否则单元格内容会落进外层缓冲区，整行表格粘成一串。
            self._cell = []
            self._in_cell = True
        elif tag in self._ROW:
            self._row = []
            self._flush_text()
        elif tag in self._BLOCK:
            self._flush_text()

    def handle_endtag(self, tag: str) -> None:
        if tag in self._SKIP:
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if self._skip_depth:
            return
        heading = re.fullmatch(r"h([1-6])", tag)
        if heading:
            text = re.sub(r"\s+", " ", " ".join(self._buffer)).strip()
            self._buffer = []
            if text:
                level = int(heading.group(1))
                self._path = self._path[: level - 1] + (text,)
            return
        if tag in self._CELL:
            value = re.sub(r"\s+", " ", " ".join(self._cell)).strip()
            self._cell = []
            self._in_cell = False
            if value:
                self._row.append(value)
            return
        if tag in self._ROW:
            self._flush_row()
            return
        if tag in self._BLOCK:
            self._flush_text()

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        if data.strip():
            (self._cell if self._in_cell else self._buffer).append(data)

    def close(self) -> None:  # noqa: D102 - 父类实现
        super().close()
        self._flush_text()
        self._flush_row()


def _html_blocks(text: str) -> list[DocumentBlock]:
    parser = _HtmlBlockParser()
    parser.feed(text)
    parser.close()
    return parser.blocks


# --------------------------------------------------------------------------- #
# 对外入口
# --------------------------------------------------------------------------- #

_MARKDOWN_EXT = frozenset({".md", ".markdown", ".mdx"})
_PLAIN_EXT = frozenset({".txt", ".text", ".log", ".rst", ".yml", ".yaml", ".json", ".sql", ".ini", ".conf"})
_CSV_EXT = frozenset({".csv", ".tsv"})
_XLSX_EXT = frozenset({".xlsx", ".xlsm"})
_PPTX_EXT = frozenset({".pptx"})
_HTML_EXT = frozenset({".html", ".htm", ".xhtml"})


def extract_blocks(content: bytes, content_type: str = "", filename: str = "") -> list[DocumentBlock]:
    """把任意受支持的文档抽成结构化块。

    两个判断规则：

    1. **扩展名优先于 content_type**。浏览器和网关给出的 content_type 经常不准
       （``.csv`` 常被报成 ``application/vnd.ms-excel``，``.md`` 被报成
       ``text/plain``），而扩展名是上传方明确给的，更可信。
    2. **具体格式在前，``text/*`` 兜底在后**。否则 ``text/csv`` 会被
       ``text/`` 前缀规则先接走，CSV 就退化成按字符切的纯文本 ——
       表格行被切碎、列名和值分家，正是要避免的情况。
    """
    suffix = Path(filename or "").suffix.lower()
    mime = (content_type or "").lower().split(";")[0].strip()

    if suffix in _CSV_EXT or mime == "text/csv":
        name = Path(filename or "").stem or "表格"
        return _rows_to_blocks(_csv_rows(_decode(content)), (name,))
    if suffix in _XLSX_EXT or "spreadsheetml" in mime:
        return _xlsx_blocks(content)
    if suffix == ".xls":
        raise ValueError("legacy .xls is not supported; please convert to .xlsx")
    if suffix in _PPTX_EXT or "presentationml" in mime:
        return _pptx_blocks(content)
    if suffix == ".ppt":
        raise ValueError("legacy .ppt is not supported; please convert to .pptx")
    if suffix in _MARKDOWN_EXT or mime in {"text/markdown", "text/x-markdown"}:
        return prose_blocks(_decode(content), markdown=True)
    if suffix in _HTML_EXT or mime in {"text/html", "application/xhtml+xml"}:
        return _html_blocks(_decode(content))
    if suffix == ".pdf" or mime == "application/pdf":
        return _pdf_blocks(content)
    if suffix == ".docx" or "wordprocessingml" in mime:
        return _docx_blocks(content)
    if suffix in _PLAIN_EXT or mime.startswith("text/"):
        return prose_blocks(_decode(content), markdown=False)
    raise ValueError(f"unsupported document type: {content_type or suffix or 'unknown'}")
