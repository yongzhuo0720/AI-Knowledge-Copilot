"""Agent 可用的工具集。

一个工具 = 一个 Python 函数 + 一份 JSON Schema 描述。真正决定「模型会不会用对工具」的
不是函数实现，而是 ``description`` —— 模型看不到代码，只能读到这段自然语言。它必须写清
三件事：**什么时候该用、什么时候不该用、参数从哪来**。实现再对、描述含糊，模型一样会用错。

三条设计约定
------------

1. **检索类工具只访问当前知识库，且知识库不由模型指定。**
   工具通过 :class:`AgentToolContext` 拿到 ``knowledge_base_id``，而不是从参数里读。
   让模型自己填知识库 id，等于把越权入口直接交给它。
2. **回灌给模型的文本必须带 ``status``。**
   「没查到」和「工具坏了」对模型是两种完全不同的处境：前者该换关键词，后者该如实告诉用户。
   只回一个裸数组时，这两种情况长得一模一样。
3. **工具不抛异常。**
   Agent 循环里抛异常会触发整体降级，一次参数笔误就被放大成整轮问答失败。
   所有失败都收敛成 ``status="error"`` 的正常返回。

顺带一个容易被忽略的点：工具返回的字段名就是模型的「视线范围」。``list_documents`` 里
把 ``document`` 写成 object_key 原值（而不是文件名），模型才有东西可以原样回传给
``knowledge_search``；只给文件名，模型就会自己拼一个路径出来。
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from dataclasses import dataclass

from app.document_blocks import extract_blocks
from app.indexing import document_inventory, document_preview_chunks, retrieve_chunks
from app.settings import settings
from app.storage import download_object

logger = logging.getLogger(__name__)

# 参数区间的唯一来源：JSON Schema 与实现里的收敛逻辑都从这里取，避免两处漂移。
SEARCH_MIN, SEARCH_MAX, SEARCH_DEFAULT = 1, 8, 5
OUTLINE_MIN, OUTLINE_MAX, OUTLINE_DEFAULT = 1, 40, 20

# 预览行的字符上限。只用于展示，不参与检索。
PREVIEW_CHARS = 80

# 文档没有标题链时，用它给这一段内容起个名字
_NO_HEADING = "（文档开头）"


# --------------------------------------------------------------------------- #
# 上下文与返回值
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class AgentToolContext:
    """工具执行上下文。

    只携带「模型无权选择」的信息。知识库 id 由服务端注入，不接受模型传参 ——
    模型是未受信任的输入，不能给它指定数据边界的权力。
    """

    knowledge_base_id: int


@dataclass(frozen=True)
class ToolStatus:
    """工具执行结果。

    :param status: ``ok`` 有内容 / ``empty`` 正常执行但没结果 / ``not_found`` 请求的对象
        不存在 / ``error`` 执行失败。后三者对模型的含义完全不同，所以必须分开：
        ``empty`` 该换关键词，``not_found`` 该先去 ``list_documents``，``error`` 该换思路。
    :param payload: 结构化数据，一份用于回灌模型，一份供前端展示。
    :param count: 语义化的「结果条数」，写进 ``agent_step.result_count``。
        由工具自己给，而不是让调用方去猜 payload 的形状（检索返回列表、清单返回字典、
        目录返回的是 ``{"sections": [...]}``）。
    """

    status: str
    payload: object
    count: int = 0


def clamp_limit(value: object, minimum: int, maximum: int, default: int) -> int:
    """把模型给的数值参数收敛到合法区间。

    模型经常给出超范围的值（``limit=50``）或干脆给个字符串。**指望 JSON Schema 里的
    ``minimum``/``maximum`` 拦住是不可靠的** —— 那只是给模型看的提示，不是强制校验，
    模型完全可以不遵守。工具侧必须自己兜住，否则一个 ``limit=100000`` 就能把知识库拖垮。
    """
    try:
        return min(max(int(value), minimum), maximum)
    except (TypeError, ValueError):
        return default


# --------------------------------------------------------------------------- #
# 小工具
# --------------------------------------------------------------------------- #


def _document_name(object_key: str) -> str:
    """从 object_key 里取出文件名。

    后端的上传路径是 ``kb/{知识库id}/{uuid}/{原始文件名}``，所以最后一段就是文件名。
    显示用文件名、传参用完整 object_key —— 前者给人看，后者给存储用。
    """
    parts = [part for part in object_key.split("/") if part]
    return parts[-1] if parts else object_key


def _short_id(object_key: str) -> str:
    """取路径里的 uuid 短前缀，用于区分**同名**文档。

    同一个知识库里可以上传两个同名文件（uuid 不同、object_key 不同）。如果清单里两行
    长得分毫不差，模型就只能瞎猜，或者干脆两个都试一遍。
    """
    parts = [part for part in object_key.split("/") if part]
    return parts[-2][:8] if len(parts) >= 2 else ""


def _first_line(chunk: str) -> str:
    """取片段的第一行作为标签。

    切片时小节标题被写在了片段开头（见 :mod:`app.chunking` 的 ``render_prefix``），
    所以首行就是这一片的主题；文档没有标题时，首行退化为正文的第一句，也还是有信息量。
    """
    for line in chunk.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped[:PREVIEW_CHARS]
    return ""


def _closest_documents(inventory: dict[str, int], wanted: str, limit: int = 3) -> list[str]:
    """挑出与 ``wanted`` 最接近的几个真实 object_key，供模型照着改。

    这个函数存在的唯一理由是实测出来的一个失败：模型**拿文件名当 object_key 传**。
    它看到 `list_documents` 里显示的名字是 ``inspection-robot.md``，就理所当然地把它
    当成参数值传回来 —— 而真正的值是 ``kb/32/<uuid>/inspection-robot.md``。

    只回一句「参数无效」模型还得自己再查一遍；把候选值直接摆在它面前，它下一轮就能改对。
    """
    wanted_name = _document_name(wanted)
    same_name = [key for key in inventory if _document_name(key) == wanted_name]
    if same_name:
        return sorted(same_name)[:limit]
    # 退一步做子串匹配：模型可能只写了文件名的一部分，或者多加了一层目录
    fuzzy = [key for key in inventory if wanted in key or key.endswith(wanted_name)]
    return sorted(fuzzy)[:limit]


# --------------------------------------------------------------------------- #
# 工具 1：知识检索
# --------------------------------------------------------------------------- #


def knowledge_search(context: AgentToolContext, arguments: dict) -> ToolStatus:
    """混合检索 + 可选的文档范围限定。"""
    query = str(arguments.get("query") or "").strip()[:1000]
    if not query:
        return ToolStatus("error", "缺少 query 参数：请给出要检索的问题或关键词。", 0)

    limit = clamp_limit(arguments.get("limit", SEARCH_DEFAULT), SEARCH_MIN, SEARCH_MAX, SEARCH_DEFAULT)
    document = str(arguments.get("document") or "").strip() or None

    # rewrite=False：Agent 路径不做查询改写。
    # 这里的 query 是**模型自己写的**，而且它会跨轮换措辞重试 —— 它本身已经在做多查询了。
    # 在工具内部再套一层改写，等于每一轮工具调用都多打一次模型，而收益与模型自己
    # 换措辞高度重叠。查询改写留给「单次问答」那条路径（见 app/query_rewrite.py）。
    hits = retrieve_chunks(context.knowledge_base_id, query, limit, document=document, rewrite=False)
    if hits:
        return ToolStatus("ok", {"results": hits}, len(hits))

    if document:
        # 空结果有两种病因，对模型的处理方式完全不同，必须分开：
        #   a) 过滤条件指了一篇不存在的文档 → 参数错了，换正确的 document 重试即可；
        #   b) 文档存在但确实没有相关内容 → 参数没错，该换关键词或去掉限定。
        # 只回一个空列表时这两种情况长得一模一样，模型只能靠猜 —— 实测里它连猜两轮把
        # 工具预算烧光了。这里加一次清单校验（只在失败路径上跑，正常检索不受影响）。
        inventory = document_inventory(context.knowledge_base_id)
        if document not in inventory:
            suggestions = _closest_documents(inventory, document)
            hint = f"当前知识库中没有文档 {document}，过滤条件无效。"
            if suggestions:
                hint += f" 你是不是想找：{'、'.join(suggestions)}？"
            hint += " document 必须取 list_documents 返回的 document 字段原值，不能只写文件名。"
            return ToolStatus("not_found", hint, 0)

    return ToolStatus(
        "empty",
        {"results": [], "message": "没有检索到相关内容，可以换一组关键词，或去掉 document 限定后重试。"},
        0,
    )


# --------------------------------------------------------------------------- #
# 工具 2：文档结构
# --------------------------------------------------------------------------- #


def document_outline(context: AgentToolContext, arguments: dict) -> ToolStatus:
    """还原某篇文档的小节结构。

    为什么要绕回 MinIO 重新下载原文，而不是拿 Milvus 里的 chunk 反推：

    1. **Milvus 里没有 ``heading_path`` 这个字段。** collection 只有 id /
       knowledge_base_id / object_key / content 四个（见
       :func:`app.indexing.index_chunks`）。切片时 ``render_prefix`` 确实把标题链
       渲染成了 ``" > "`` 拼的前缀、并且**打进了 chunk 正文**（见
       :func:`app.chunking.chunk_blocks`），但那是给人读的字符串，**元组本身没入库**。
    2. **走 Milvus 是检索语义，不是枚举语义。** 拿不全这篇文档的所有片段，就拼不出
       完整的小节树；而用 ``" > "`` 去切字符串反推层级，还会因为标题本身含 ``>``
       而切错。

    所以只能重新取回原始文档再 ``extract_blocks`` 一遍 —— 这也是为什么这个工具需要
    :mod:`app.storage` 里的下载能力。
    """
    document = str(arguments.get("document") or "").strip()
    if not document:
        return ToolStatus("error", "缺少 document 参数：请先调用 list_documents 取得文档标识。", 0)
    limit = clamp_limit(arguments.get("limit", OUTLINE_DEFAULT), OUTLINE_MIN, OUTLINE_MAX, OUTLINE_DEFAULT)

    # 越权防线：object_key 是共享对象存储里的路径，模型可以编造出别的知识库的 key。
    # 先确认它确实属于当前知识库，再决定要不要把内容读出来。
    if document not in document_inventory(context.knowledge_base_id):
        return ToolStatus(
            "not_found",
            f"当前知识库中没有文档 {document}。请先用 list_documents 查看可用文档的 document 值。",
            0,
        )

    try:
        content = download_object(document)
    except Exception as exc:  # 网络/存储异常不该让整轮问答失败
        logger.warning("document_outline failed to download %s", document, exc_info=True)
        return ToolStatus("error", f"读取文档失败：{exc}", 0)

    name = _document_name(document)
    try:
        # content_type 留空、按文件名后缀路由：object_key 末尾保留了原始扩展名，
        # 足够 extract_blocks 判断类型，而这里拿不到可靠的 MIME。
        blocks = extract_blocks(content, "", name)
    except Exception as exc:
        logger.warning("document_outline failed to parse %s", document, exc_info=True)
        return ToolStatus("error", f"解析文档失败：{exc}", 0)

    order: list[tuple[str, ...]] = []
    counts: dict[tuple[str, ...], int] = {}
    for block in blocks:
        if not (block.text or "").strip():
            continue
        path = block.heading_path
        if path not in counts:
            order.append(path)
            counts[path] = 0
        counts[path] += 1

    if not order:
        return ToolStatus(
            "empty",
            {"document": document, "name": name, "message": "该文档没有可抽取的结构（可能是空文档，或只含标题）。"},
            0,
        )

    if not any(path for path in order):
        # 整篇没有标题：给开头几段文字。这时候「结构」不存在，但「这是什么文档」还有答案。
        head = "\n".join((block.text or "").strip() for block in blocks[:3])[:600]
        return ToolStatus(
            "ok",
            {
                "document": document,
                "name": name,
                "section_count": 0,
                "sections": [],
                "head": head,
                "note": "该文档没有标题结构，以下是开头内容；可据此判断它是否值得进一步检索。",
            },
            0,
        )

    sections = [{"heading": " > ".join(path) or _NO_HEADING, "blocks": counts[path]} for path in order]
    return ToolStatus(
        "ok",
        {
            "document": document,
            "name": name,
            "section_count": len(sections),
            "sections": sections[:limit],
            "truncated": len(sections) > limit,
        },
        len(sections),
    )


# --------------------------------------------------------------------------- #
# 工具 3：文档清单
# --------------------------------------------------------------------------- #


def list_documents(context: AgentToolContext, _arguments: dict) -> ToolStatus:
    """列出知识库里已建索引的文档。

    「有哪些文档」这类问题靠检索答不出来 —— 检索是按相关性召回片段，没有「枚举」这个语义。
    这也正是要给 Agent 加第二个工具的理由：**工具的能力边界就是模型的能力边界**。
    """
    inventory = document_inventory(context.knowledge_base_id)
    if not inventory:
        return ToolStatus(
            "empty",
            {"document_count": 0, "documents": [], "message": "当前知识库还没有任何已建索引的文档。"},
            0,
        )

    named = {key: _document_name(key) for key in inventory}
    seen = Counter(named.values())
    preview_count = max(0, settings.agent_tool_preview_chunks)

    documents: list[dict[str, object]] = []
    for key in sorted(inventory, key=lambda item: (named[item], item)):
        label = named[key]
        if seen[label] > 1:
            label = f"{label}（{_short_id(key)}）"
        documents.append({
            # document 是模型要原样回传给其它工具的取值，必须是 object_key 本身
            "document": key,
            "name": label,
            "chunks": inventory[key],
            "preview": [
                line for line in (
                    _first_line(chunk)
                    for chunk in document_preview_chunks(context.knowledge_base_id, key, preview_count)
                ) if line
            ],
        })

    return ToolStatus("ok", {"document_count": len(documents), "documents": documents}, len(documents))


# --------------------------------------------------------------------------- #
# 工具清单（给模型的说明书）
# --------------------------------------------------------------------------- #

TOOLS: list[dict[str, object]] = [
    {
        "type": "function",
        "function": {
            "name": "knowledge_search",
            "description": (
                "在知识库中做混合检索（向量 + 关键词 + 重排），返回最相关的文档片段及出处。"
                "适用于「事实型」问题：某个流程怎么走、某个配置的值是多少、某个错误码什么含义。"
                "回答任何涉及知识库内容的问题前都必须先调用本工具，不要凭记忆作答。"
                "若已知要查的是某一篇文档，把该文档的 document 传进来可缩小范围、提高准确率；"
                "不确定文档标识时，先用 list_documents 取得。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "要检索的问题或关键词，用自然语言表述即可"},
                    "limit": {
                        "type": "integer",
                        "minimum": SEARCH_MIN,
                        "maximum": SEARCH_MAX,
                        "default": SEARCH_DEFAULT,
                        "description": "返回的片段数，问题越宽泛可取越多",
                    },
                    "document": {
                        "type": "string",
                        "description": (
                            "可选。只在指定文档内检索。取值必须是 list_documents 返回的 document 字段"
                            "原值（object_key），不要自己按文件名拼一个路径。"
                        ),
                    },
                },
                "required": ["query"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_documents",
            "description": (
                "列出当前知识库中所有已建索引的文档，给出文件名、片段数，以及每篇的几个内容预览片段。"
                "用于回答「这个知识库有哪些文档」，或在对知识库内容一无所知时先摸清范围。"
                "当用户的问题明显指向某一篇文档、而你不知道它的标识时，也先调用本工具。"
                "不需要传任何参数。"
            ),
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "document_outline",
            "description": (
                "查看某一篇文档的章节结构：每个小节的标题链与段落/行/页数。"
                "用于判断「答案可能在哪个小节」，或用户直接问「这篇文档讲了什么」时。"
                "需要该文档的 document 值（从 list_documents 取得）。"
                "注意两点：只能识别 # 形式的标题，图片型或纯文本排版的文档取不到结构；"
                "若文档完全没有标题，返回的是开头几行文字而不是结构。"
                "普通的查事实问题不需要本工具，直接用 knowledge_search 即可。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "document": {
                        "type": "string",
                        "description": "要查看的文档，取 list_documents 返回的 document 字段原值",
                    },
                    "limit": {
                        "type": "integer",
                        "minimum": OUTLINE_MIN,
                        "maximum": OUTLINE_MAX,
                        "default": OUTLINE_DEFAULT,
                        "description": "最多返回多少个小节",
                    },
                },
                "required": ["document"],
                "additionalProperties": False,
            },
        },
    },
]

_HANDLERS = {
    "knowledge_search": knowledge_search,
    "list_documents": list_documents,
    "document_outline": document_outline,
}


# --------------------------------------------------------------------------- #
# 分发与展示
# --------------------------------------------------------------------------- #


def describe_call(name: str, arguments: dict) -> str:
    """把一次工具调用转成人类可读的一句话，写进 ``agent_step.query`` 供前端展示。

    为什么需要它：``agent_step.query`` 是 VARCHAR(1000) 且前端直接显示，把工具的原始
    JSON 参数塞进去，执行轨迹面板上就会出现一行 ``{"query":"...","limit":5}``，
    对用户毫无意义。这里转成「检索『发布流程』，取前 5 片」这样的说法。

    ⚠️ 返回值**必须**截断到 1000 字符以内：query 列是 VARCHAR(1000)，超长会让整条消息的
    落库 INSERT 直接报错 —— 失败的是「保存消息」，报错信息却指向一个跟展示无关的字段。
    """
    arguments = arguments if isinstance(arguments, dict) else {}

    if name == "knowledge_search":
        query = str(arguments.get("query") or "").strip()
        limit = clamp_limit(arguments.get("limit", SEARCH_DEFAULT), SEARCH_MIN, SEARCH_MAX, SEARCH_DEFAULT)
        document = str(arguments.get("document") or "").strip()
        parts = [f"检索「{query or '（未给出关键词）'}」"]
        if document:
            parts.append(f"范围：{_document_name(document)}")
        parts.append(f"取前 {limit} 片")
        description = "，".join(parts)
    elif name == "document_outline":
        document = str(arguments.get("document") or "").strip()
        description = f"查看《{_document_name(document) or '（未指定文档）'}》的目录"
    elif name == "list_documents":
        description = "列出知识库中的全部文档"
    else:
        description = f"调用工具 {name or '（未命名）'}"

    return description[:1000]


def run_tool(context: AgentToolContext, name: str, arguments: dict) -> tuple[ToolStatus, str]:
    """执行一个工具，返回 ``(结构化结果, 回灌给模型的文本)``。

    未知工具**返回 error 而不是抛异常**：模型拼错工具名是很常见的失败，抛异常会让整个
    Agent 循环降级成「不带工具的普通问答」，把一次笔误放大成整轮能力损失。
    """
    handler = _HANDLERS.get(name)
    if handler is None:
        status = ToolStatus("error", f"未知工具 {name}。可用工具：{'、'.join(_HANDLERS)}。", 0)
    else:
        try:
            status = handler(context, arguments)
        except Exception:
            logger.exception("agent tool %s failed", name)
            status = ToolStatus("error", f"工具 {name} 执行出错，请换一种方式获取信息。", 0)

    return status, json.dumps({"status": status.status, "data": status.payload}, ensure_ascii=False)
