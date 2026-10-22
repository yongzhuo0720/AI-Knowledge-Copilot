"""生成「新格式」评估语料：xlsx / pptx / csv / html。

## 为什么要有这个脚本，而不是直接放几个二进制文件进仓库

二进制文件在 code review 里是一片黑箱 —— 没人看得出里面写了什么、改了什么。
把生成过程写成脚本，语料的**每一句事实**都是可读、可 diff、可修改的；
需要调整内容时改脚本重跑，而不是去找一个能编辑 xlsx 的工具。

## 为什么用标准库手写 OOXML

xlsx / pptx 本质就是 ZIP + XML，而 ai-service 的解析器（``app/document_blocks.py``）
同样是标准库实现（刻意不引入 openpyxl / python-pptx，理由见那边的注释）。
这里保持一致：生成端也不引入额外依赖，于是「装得上、跑得起来」这件事在两端都成立。

## 语料设计原则

每条要考的事实都必须**只存在于这份新格式文档里**，且能被两段字面串锁定
（例如 ``8600`` + ``120000``）—— 判分用的是 chunk 级双条件匹配，
单串太容易被别的文档碰巧命中。

用法：
    python docs/rag-eval/build_new_format_corpus.py            # 写进 corpus/
    python docs/rag-eval/build_new_format_corpus.py --verify   # 生成后用真实解析器自检
"""

from __future__ import annotations

import argparse
import csv
import io
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

CORPUS = Path(__file__).resolve().parent / "corpus"

# --------------------------------------------------------------------------- #
# 语料内容（改这里，不要改下面的生成代码）
# --------------------------------------------------------------------------- #

# 表格：网关容量表。型号刻意另起一个 8xxx 段位，避免与 gateway-model-catalog.md 的
# 2xxx~9xxx（百位）撞号 —— 但 9xxx 已占，所以用 8xxx 并且是 200 的步长。
CAPACITY_HEADER = ["型号", "最大并发连接数", "整机功耗", "冗余电源"]
CAPACITY_ROWS = [
    ["XZ-8200", "20000", "18 瓦", "不支持"],
    ["XZ-8400", "60000", "24 瓦", "不支持"],
    ["XZ-8600", "120000", "32 瓦", "支持"],
]

POWER_HEADER = ["项目", "取值"]
POWER_ROWS = [
    ["冗余电源切换时间", "8 毫秒"],
    ["整机待机功耗", "3 瓦"],
]

TICKET_HEADER = ["工单号", "设备编号", "故障码", "处理时长分钟", "处理人"]
TICKET_ROWS = [
    ["INC-20260413", "XZ-7700", "E2051", "185", "张工"],
    ["INC-20260414", "XZ-5500", "E2033", "45", "李工"],
    ["INC-20260415", "XZ-8400", "E2051", "92", "王工"],
]

RUNBOOK_HTML = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>现场运维值班手册</title>
  <style>body { font-family: sans-serif; }</style>
</head>
<body>
  <h1>现场运维值班手册</h1>
  <p>本手册供值班工程师在交接班与突发故障时查阅。</p>

  <h2>交接班流程</h2>
  <p>夜间交接班必须在 22:30 之前完成设备清单核对，并在值班日志上双方签字。</p>
  <p>交接内容包括在线设备数量、未闭环工单、以及待处理的固件升级任务。</p>

  <h2>应急联系方式</h2>
  <p>值班应急电话为 4008-517-208，转 3 号键进入二线支持。</p>

  <h2>升级窗口</h2>
  <p>除紧急修复外，固件升级只允许在每周三凌晨 1:00 到 4:00 之间执行。</p>

  <script>console.log("这段脚本内容不该进入索引");</script>
</body>
</html>
"""

# 幻灯片：每页首个非空段落被视为标题，其余段落是正文。
SLIDES = [
    ("V16 版本发布说明", ["本次发布引入查询改写（Multi-Query）能力。", "适用范围：全部在线知识库。"]),
    ("上线范围", ["灰度批次代号 R16-A，覆盖 20% 的租户。", "观察期为七个自然日。"]),
    ("回滚条件", ["灰度期内错误率超过千分之三即执行回滚。", "回滚动作由值班工程师发起。"]),
]


# --------------------------------------------------------------------------- #
# 极简 OOXML 写入
# --------------------------------------------------------------------------- #

def _column_name(index: int) -> str:
    """0 → A、25 → Z、26 → AA。Excel 列名是 26 进制但没有 0。"""
    name = ""
    index += 1
    while index:
        index, remainder = divmod(index - 1, 26)
        name = chr(ord("A") + remainder) + name
    return name


def _shared_strings(rows: list[list[str]]) -> tuple[list[str], dict[str, int]]:
    """把所有文本单元格收进共享字符串表，返回（表, 文本→下标）。"""
    table: list[str] = []
    index: dict[str, int] = {}
    for row in rows:
        for cell in row:
            if cell not in index:
                index[cell] = len(table)
                table.append(cell)
    return table, index


def _sheet_xml(rows: list[list[str]], index: dict[str, int]) -> str:
    lines = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for row_number, row in enumerate(rows, start=1):
        cells = []
        for column, value in enumerate(row):
            reference = f"{_column_name(column)}{row_number}"
            # t="s" 表示「值是一个共享字符串表下标」，这正是解析器要回填的那种写法。
            cells.append(f'<c r="{reference}" t="s"><v>{index[value]}</v></c>')
        lines.append(f'<row r="{row_number}">{"".join(cells)}</row>')
    lines.append("</sheetData></worksheet>")
    return "".join(lines)


def write_xlsx(path: Path, sheets: list[tuple[str, list[list[str]]]]) -> None:
    all_rows = [row for _, rows in sheets for row in rows]
    table, index = _shared_strings(all_rows)

    workbook_sheets = "".join(
        f'<sheet name="{escape(name)}" sheetId="{number}" r:id="rId{number}"/>'
        for number, (name, _) in enumerate(sheets, start=1)
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f"<sheets>{workbook_sheets}</sheets></workbook>"
    )
    relationships = "".join(
        '<Relationship Id="rId{number}" Type="http://schemas.openxmlformats.org/'
        'officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{number}.xml"/>'.format(number=number)
        for number, _ in enumerate(sheets, start=1)
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        f"{relationships}"
        '<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/'
        'officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>'
        "</Relationships>"
    )
    shared = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="{len(table)}" '
        f'uniqueCount="{len(table)}">'
        + "".join(f"<si><t>{escape(value)}</t></si>" for value in table)
        + "</sst>"
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-'
        'spreadsheetml.sheet.main+xml"/>'
        + "".join(
            f'<Override PartName="/xl/worksheets/sheet{number}.xml" ContentType="application/vnd.'
            'openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            for number, _ in enumerate(sheets, start=1)
        )
        + '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-'
        'officedocument.spreadsheetml.sharedStrings+xml"/>'
        "</Types>"
    )

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels",
                         '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                         '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                         '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/'
                         'officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                         "</Relationships>")
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", rels)
        archive.writestr("xl/sharedStrings.xml", shared)
        for number, (_, rows) in enumerate(sheets, start=1):
            archive.writestr(f"xl/worksheets/sheet{number}.xml", _sheet_xml(rows, index))


def write_pptx(path: Path, slides: list[tuple[str, list[str]]]) -> None:
    slide_parts = []
    for index, (title, body) in enumerate(slides, start=1):
        paragraphs = "".join(
            '<a:p><a:r><a:t>{}</a:t></a:r></a:p>'.format(escape(text))
            for text in [title, *body]
        )
        slide_parts.append((
            f"ppt/slides/slide{index}.xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
            'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
            f"<p:cSld><p:spTree>{paragraphs}</p:spTree></p:cSld></p:sld>",
        ))

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml",
                         '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                         '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                         '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.'
                         'relationships+xml"/><Default Extension="xml" ContentType="application/xml"/></Types>')
        for name, payload in slide_parts:
            archive.writestr(name, payload)


# --------------------------------------------------------------------------- #

def build() -> list[Path]:
    CORPUS.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    xlsx = CORPUS / "gateway-capacity.xlsx"
    write_xlsx(xlsx, [("容量表", [CAPACITY_HEADER, *CAPACITY_ROWS]), ("供电说明", [POWER_HEADER, *POWER_ROWS])])
    written.append(xlsx)

    pptx = CORPUS / "release-briefing.pptx"
    write_pptx(pptx, SLIDES)
    written.append(pptx)

    csv_path = CORPUS / "incident-tickets.csv"
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(TICKET_HEADER)
    writer.writerows(TICKET_ROWS)
    # newline="" + 显式 utf-8：中文环境里用默认编码写出的 CSV 会被解析器当成 GBK 读，
    # 结果是一串乱码进索引（而且**不报错**）。
    csv_path.write_text(buffer.getvalue(), encoding="utf-8", newline="")
    written.append(csv_path)

    html_path = CORPUS / "ops-runbook.html"
    html_path.write_text(RUNBOOK_HTML, encoding="utf-8")
    written.append(html_path)

    return written


def verify() -> int:
    """用**真实的解析器 + 真实的切片器**回读刚生成的文件，确认事实真的能被检索到。

    这一步不能省。手写 OOXML 最容易犯的错是「文件生成了、但解析器读出来是空的」——
    共享字符串表下标对不上、``r:id`` 没连上、单元格引用写错，都会静静地产生空文档。

    而且判据必须是**切片后的 chunk 正文**，不是「块正文」：标题（``<h2>``、幻灯片首页、
    工作表名）不会作为块正文出现，它进的是 heading_path，切片时被拼成前缀写进 chunk。
    第一版自检就是只查了块正文，于是把「V16」「交接班流程」这两个**确实能被检索到**的
    事实误报成缺失 —— 查错了对象，比不查更误导。
    """
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "ai-service"))
    from app.chunking import chunk_blocks  # noqa: PLC0415
    from app.document_blocks import extract_blocks  # noqa: PLC0415

    expectations = {
        "gateway-capacity.xlsx": ["120000", "XZ-8600", "8 毫秒", "冗余电源"],
        "release-briefing.pptx": ["R16-A", "千分之三", "V16", "Multi-Query"],
        "incident-tickets.csv": ["INC-20260413", "E2051", "185", "处理时长分钟"],
        "ops-runbook.html": ["22:30", "4008-517-208", "交接班流程"],
    }

    failures: list[str] = []
    for name, needles in expectations.items():
        raw = (CORPUS / name).read_bytes()
        blocks = extract_blocks(raw, "", name)
        chunks = chunk_blocks(blocks)
        text = "\n".join(chunks)

        print(f"\n=== {name} ===")
        print(f"  块数：{len(blocks)}    切片数：{len(chunks)}")
        for chunk in chunks:
            print(f"  · {chunk[:120].replace(chr(10), ' ⏎ ')}")
        for needle in needles:
            if needle in text:
                print(f"  [OK]   {needle}")
            else:
                print(f"  [MISS] {needle}")
                failures.append(f"{name}: {needle}")

    # <script> 里的内容不该进索引（HTML 解析器要跳过 script/style/head）
    runbook = "\n".join(chunk_blocks(extract_blocks(
        (CORPUS / "ops-runbook.html").read_bytes(), "", "ops-runbook.html")))
    if "这段脚本内容不该进入索引" in runbook:
        print("\n  [FAIL] <script> 里的内容被索引了")
        failures.append("ops-runbook.html: script 内容泄漏")
    else:
        print("\n  [OK]   <script> 内容已被排除")

    if failures:
        print(f"\n自检失败 {len(failures)} 项：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("\n自检全部通过。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="生成新格式评估语料")
    parser.add_argument("--verify", action="store_true", help="生成后用真实解析器回读自检")
    arguments = parser.parse_args()

    for path in build():
        print(f"已生成 {path.relative_to(CORPUS.parents[1])}（{path.stat().st_size} 字节）")

    return verify() if arguments.verify else 0


if __name__ == "__main__":
    raise SystemExit(main())
