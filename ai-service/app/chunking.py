"""结构感知切片：只在块的边界上切，并给每片补上所属小标题。

## 为什么不是「固定字符滑窗」

原来的 ``split_text`` 做两件事：先把所有空白（含换行）压成单个空格，再按
800 字符等距切开。两个动作都有问题：

- **压平空白**丢掉了段落边界，标题和正文糊成一团，后面再想按结构切也没有依据；
- **等距切**完全不看内容，会把一个段落、一个表格行从中间切断。

## 三条规则

1. **只在不越界的地方切**：块与块之间才是合法边界。段落、表格行、PDF 页
   都各自完整地落进某一篇 chunk。
2. **标题变化是硬边界**：一个小节的开头必然另起一片。这样一片 chunk
   不会同时讲两件事，标题前缀才有意义。
3. **超大块内部才用字符滑窗**：一个段落本身就超过 size（长表格行、PDF 整页），
   这时退回到带 overlap 的滑窗，保证内容不丢。

## 为什么「标题前缀」比调 overlap 更值

overlap 是在猜「答案会不会正好横跨边界」，靠重叠窗口碰运气，代价是内容重复、
索引变大。标题前缀是**确定的信息**：切片时我们本来就知道这一段属于哪个小节，
把它写进 chunk 开头，等于给每片免费补上了主题上下文。

对检索的实际影响：查询「覆盖索引为什么能变快」时，即使命中的那一块正文里
没再出现「覆盖索引」四个字，前缀里有，BM25 和向量都能对上。
"""

from app.document_blocks import DocumentBlock, extract_blocks, prose_blocks

# 相邻多级标题的拼接符。用 ``>`` 是因为它天然表达层级，且不会被中文分词切碎。
_PATH_SEPARATOR = " > "


def render_prefix(heading_path: tuple[str, ...]) -> str:
    """把标题链渲染成 chunk 前缀。空标题链返回空串。"""
    if not heading_path:
        return ""
    return _PATH_SEPARATOR.join(title for title in heading_path if title)


def windows(text: str, size: int, overlap: int) -> list[str]:
    """字符滑窗 —— 只在「单个块本身就超过 size」时使用，正常路径走不到这里。

    两个刻意的选择：

    1. **只超出预算不到一个 step 时根本不切。** 例如 801 字的段落按 800 切，
       两片会重叠 799 字 —— 等于把同一段文本索引两遍，还白占一个检索名额。
       这种情况直接整块保留（``size`` 是预算不是硬上限）。
    2. **起点均匀分布**，而不是「按 step 递推、末尾再对齐」。递推会让最后一片
       要么是个几十字的孤儿片段，要么和上一片重叠过多；均匀分布则保证每片长度
       恰好等于 ``size``、相邻片重叠不少于 ``overlap``，且实际片数最少。
    """
    if size <= 0:
        raise ValueError("size must be positive")
    if len(text) <= size:
        return [text]

    step = max(1, size - max(0, overlap))
    span = len(text) - size
    if span < step:
        return [text]

    count = -(-span // step)  # 向上取整
    starts = [round(index * span / count) for index in range(count + 1)]
    return [text[start:start + size] for start in starts]


def chunk_blocks(
    blocks: list[DocumentBlock],
    size: int = 800,
    overlap: int = 120,
) -> list[str]:
    """把结构化块打包成 chunk。

    :param size: 每片正文的目标字符上限（标题前缀不计入，它是元信息）
    :param overlap: 仅在块内部滑窗时生效的重叠长度
    """
    if size <= 0:
        raise ValueError("size must be positive")

    chunks: list[str] = []
    buffer: list[str] = []
    buffered = 0
    path: tuple[str, ...] = ()

    def flush() -> None:
        nonlocal buffer, buffered
        if buffer:
            prefix = render_prefix(path)
            body = "\n".join(buffer)
            chunks.append(f"{prefix}\n\n{body}" if prefix else body)
            buffer = []
            buffered = 0

    for block in blocks:
        text = (block.text or "").strip()
        if not text:
            continue
        if block.heading_path != path:
            # 标题变化 = 新小节开始，必须另起一片
            flush()
            path = block.heading_path

        if len(text) > size:
            # 单块超长：先落盘已有内容，再对本块做滑窗，避免和其他块混在一片里
            flush()
            prefix = render_prefix(path)
            chunks.extend(
                f"{prefix}\n\n{piece}" if prefix else piece
                for piece in windows(text, size, overlap)
            )
            continue

        # +1 是拼接用的换行符，预算要算进去，否则实际长度会超 size
        if buffered and buffered + len(text) + 1 > size:
            flush()
        buffer.append(text)
        buffered += len(text) + 1

    flush()
    return chunks


def chunk_document(
    content: bytes,
    content_type: str = "",
    filename: str = "",
    size: int = 800,
    overlap: int = 120,
) -> list[str]:
    """一步到位：字节流 → 结构化块 → chunk 列表。"""
    return chunk_blocks(extract_blocks(content, content_type, filename), size, overlap)


def chunk_text(text: str, size: int = 800, overlap: int = 120) -> list[str]:
    """纯文本入口，供不经过文件类型的调用方使用。

    自动识别 Markdown 标题：只要正文里出现了 ATX 标题（``#`` 开头），
    就按 Markdown 解析；否则当普通文本按空行分段。这样 ``split_text`` 的
    既有调用方在传入 Markdown 时也能白拿结构感知的好处。
    """
    looks_markdown = any(
        line.lstrip().startswith("#")
        for line in text.splitlines()[:200]
    )
    return chunk_blocks(prose_blocks(text, markdown=looks_markdown), size, overlap)
