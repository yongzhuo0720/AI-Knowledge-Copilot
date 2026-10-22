"""查询改写（Multi-Query）：把用户的一句话换成几种说法，分别召回后再融合。

## 为什么需要它

现成的两路召回已经把「意思」和「字面」都覆盖了：

- 向量检索对「意思」负责 —— 换个说法也能搜到；
- BM25 对「字面」负责 —— 编号、型号、错误码必须原样命中。

但它们**都只在用户那一种表述上工作**。如果这个表述恰好偏离了语料的用词，
两路会一起失效。典型场景：

- 用户：「报表导出特别慢怎么办」   文档：「导出性能优化」
- 用户：「设备掉线了怎么恢复」     文档：「断连重连流程」

词面上没有重合，意思上又不够接近，两边都召不回来。
多查询的做法是：先用模型把问题改写成几种不同说法，各自召回一遍，
再把所有结果按 RRF 融合 —— 命中**任意一种**说法的片段都有机会进来。
而 RRF 只看名次，「被多种说法同时召回」的片段自然被抬到前面，
这恰好就是「答案」的特征。

## 为什么它必须是 fail-open 的

改写在检索**之前**。如果它失败（没配 key、超时、网络抖动、模型返回垃圾），
绝不能连带让检索也失败 —— 那等于把「多召回一点」的增强，
变成了「整个问答不可用」的故障。

所以 :func:`rewrite_query` 在任何异常下都返回**空列表**，
调用方（``hybrid_search.multi_query_retrieve``）据此退回单查询。这不是兜底代码，
而是这个模块唯一正确的失败语义：**「改写不出来」和「只用一个查询」是同一件事。**

## 为什么它不进入 Agent 的工具检索

Agent 的 ``knowledge_search`` 拿到的是**模型自己写的**查询，而且模型会跨轮改写重试 ——
它本身已经在做「多查询」了。在工具内部再套一层改写，等于每轮多打一次模型，
而收益与模型自己换措辞高度重叠。所以那条路径显式关掉（见 ``agent_tools.knowledge_search``）。
"""

from __future__ import annotations

import json
import logging
import re
from urllib.request import Request, urlopen

from app.settings import settings

logger = logging.getLogger(__name__)

REWRITE_PROMPT = """你是检索系统的查询改写器。用户要在一个知识库里搜索资料，\
但知识库文档的用词往往和用户提问不一致。

请把下面的问题改写成 {count} 条**说法不同、意图相同**的检索查询：
- 用文档里更可能出现的正式书面表述，去掉口语化的语气词；
- 可以替换同义词、补充该领域的常见术语或上位词；
- **问题里的编号、型号、版本号、错误码必须原样保留**，不要改写、不要翻译；
- 不要回答问题，不要解释，不要输出引号或编号。

每行一条，直接开始输出。

问题：{question}"""

# 模型很爱加 "- "、"1. "、"1）" 这类前缀，即使明确说了不要编号。
# 与其和它较劲，不如在解析时统一剥掉 —— 提示词负责「尽量」，解析负责「一定」。
_LINE_PREFIX = re.compile(r"^\s*(?:[-*•·]|\(?\d+\s*[.)、）]|[（(]\s*\d+\s*[）)])\s*")
# 中英文的成对引号都要覆盖。模型换用哪种引号是不确定的，
# 漏掉一对就会让那一条改写带着引号进检索 —— 引号会被当作查询的一部分参与分词。
_WRAPPING_QUOTES = "\"'“”‘’`「」『』《》〈〉【】〔〕[]（）()"

# 一条检索查询的长度上限。超了基本说明模型跑偏去写解释或答案了，
# 与其把噪声塞进检索，不如丢掉它。
MAX_REWRITE_LENGTH = 200


def _build_prompt(question: str, count: int) -> str:
    return REWRITE_PROMPT.format(count=count, question=question)


def _strip_decoration(line: str) -> str:
    """把一行输出洗成裸查询：去编号前缀、去包裹的引号、去首尾空白。"""
    return _LINE_PREFIX.sub("", line.strip()).strip().strip(_WRAPPING_QUOTES).strip()


def parse_rewrites(raw: str, question: str, count: int) -> list[str]:
    """从模型输出里解析出改写结果。

    过滤规则每一条都对应一种真实会出现的脏输出：

    - 空行 / 只剩标点的行 —— 模型用空行分段；
    - 与原问题相同的行 —— 那样就退化成「同一路召回跑两遍」，白白占一个融合名额；
    - 重复行 —— 同样会稀释融合权重（RRF 里重复出现等于给它投两票）；
    - 过长的行 —— 模型开始解释或直接答题了。

    大小写不敏感地去重，是因为模型有时只改了个大小写。
    """
    seen = {question.strip().lower()}
    rewrites: list[str] = []

    for line in raw.splitlines():
        candidate = _strip_decoration(line)
        if not candidate or len(candidate) < 2 or len(candidate) > MAX_REWRITE_LENGTH:
            continue
        key = candidate.lower()
        if key in seen:
            continue
        seen.add(key)
        rewrites.append(candidate)
        if len(rewrites) >= count:
            break

    return rewrites


def _call_chat(prompt: str) -> str:
    request = Request(
        f"{settings.chat_base_url.rstrip('/')}/chat/completions",
        data=json.dumps({
            "model": settings.chat_model,
            "messages": [{"role": "user", "content": prompt}],
            # 比作答高得多的温度：改写要的是「不同的说法」，
            # 温度太低时模型会反复给出几乎相同的句子，等于没改写。
            "temperature": 0.8,
        }, ensure_ascii=False).encode(),
        headers={"Authorization": f"Bearer {settings.chat_api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=settings.query_rewrite_timeout_seconds) as response:
        return json.load(response)["choices"][0]["message"]["content"]


def rewrite_query(question: str, count: int | None = None) -> list[str]:
    """把 ``question`` 改写成若干条检索查询。

    **任何失败都返回空列表**，永不抛异常 —— 调用方把空列表理解为
    「只用原问题检索」，于是没有配置模型、模型超时、返回乱码时，
    系统行为与没有这个功能时完全一致。
    """
    count = settings.query_rewrite_count if count is None else count
    question = (question or "").strip()

    if count <= 0 or not question:
        return []
    if not settings.chat_api_key:
        # 明确不告警：本地开发没配 key 是正常状态，不该刷日志。
        return []

    try:
        raw = _call_chat(_build_prompt(question, count))
    except Exception:
        # 这里要告警：配了 key 却失败是异常情况，多半是超时或额度问题，
        # 不记下来的话表现只是「检索好像没变好」，几乎无法排查。
        logger.warning("query rewrite failed; falling back to the original query only", exc_info=True)
        return []

    return parse_rewrites(raw, question, count)
