"""知识库 Agent：让模型自己决定调哪些工具、调几次，再依据工具返回的资料作答。

## 一次问答的完整闭环

1. 把系统提示 + 历史 + 用户问题发给模型，同时附上 :data:`app.agent_tools.TOOLS` 这套工具说明书；
2. 模型要么直接回答（结束），要么给出若干个 ``tool_calls``；
3. 逐个执行工具，把结果作为 ``role="tool"`` 的消息追加回对话；
4. 回到第 1 步，把带上了工具结果的对话再发给模型。

最多循环 :attr:`app.settings.Settings.agent_max_rounds` 轮 —— 必须有上限，否则模型可以
无限地「再查一次」，一次提问就能烧掉不可控的时间和 token。

## 三个容易写错的地方

- **工具结果不能当成最终答案。** ``answer_question`` 只在降级路径上用；正常路径下最终回答
  永远来自模型正文。不然「工具调用顺序」会变成影响答案的隐藏变量，模型就不再真正驱动回答了。
- **同一次问答内结果可以复用。** 模型一轮里重复给出两个一样的 ``list_documents``、或者第二轮
  又问一遍同样的问题，都很常见。缓存按「工具名 + 规范化后的参数」做键，命中就不再执行。
- **只有检索结果算证据。** 文档清单和目录是「导航信息」，把它们的返回值并进 ``sources``，
  前端就会把一份和问题无关的文档清单列成引用来源。
"""

import json
import logging
from urllib.request import Request, urlopen

from app.agent_tools import TOOLS, AgentToolContext, describe_call, run_tool
from app.answering import answer_question
from app.settings import settings

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "你是企业知识库 Agent，可以调用工具查阅资料后再回答。\n"
    "工作方式：\n"
    "- 任何涉及知识库内容的问题（流程、配置、错误码、政策、规范），都必须先调用 "
    "knowledge_search 取回原文，不要凭记忆作答；\n"
    "- 如果不知道知识库里有哪些文档，先调用 list_documents 摸清范围；\n"
    "- 已经能确定问题指向某一篇文档时，把该文档传给 knowledge_search 的 document 参数，"
    "可以缩小范围、提高准确率；\n"
    "- 需要了解某篇文档的整体结构（用户问「这篇讲了什么」，或要判断去哪个小节找）时，"
    "调用 document_outline；\n"
    "- document 参数**只能是 list_documents 返回的 document 字段原值**（形如 "
    "kb/12/uuid/文件名）。文件名不是合法的 document 值，传文件名会查不到任何内容。\n"
    "回答要求：\n"
    "- 只依据工具返回的资料回答，资料不足就明确说明「知识库中没有相关内容」，不要编造；\n"
    "- 引用资料时用 [1]、[2] 这样的编号标注来源，编号顺序与资料顺序一致。"
)


def run_knowledge_agent(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    """执行一次 Agent 问答，返回 ``{answer, sources, steps}``。

    :param knowledge_base_id: 知识库 id，由服务端注入，模型无权指定
    :param history: 之前的对话消息（``[{"role": "user"/"assistant", "content": ...}]``）
    """
    if not settings.chat_api_key:
        # 没配模型密钥时整条链路不能断 —— 直接退化成「检索 + 模板回答」，功能降级但不失效
        return _degraded_result(knowledge_base_id, question, history, reason="未配置模型密钥")

    messages: list[dict[str, object]] = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend({"role": item["role"].lower(), "content": item["content"]} for item in (history or []))
    messages.append({"role": "user", "content": question})

    context = AgentToolContext(knowledge_base_id=knowledge_base_id)
    sources: list[dict[str, object]] = []
    steps: list[dict[str, object]] = []
    # 缓存键用「工具名 + 规范化参数」：sort_keys 保证 {"a":1,"b":2} 和 {"b":2,"a":1} 命中同一条。
    # 键里必须带上参数，否则同一轮里对不同文档调用 document_outline 会互相串结果。
    # 注意这里只做「字面规范化」，不做语义等价判断 —— 显式写了默认值（limit=5）和省略它
    # 会被当成两次调用。这点浪费远比「猜错语义导致返回错数据」划算。
    executed: dict[tuple[str, str], tuple[object, str]] = {}

    try:
        for _ in range(max(1, settings.agent_max_rounds)):
            response = _chat_completion(messages, TOOLS)
            assistant_message = response["choices"][0]["message"]
            tool_calls = assistant_message.get("tool_calls") or []
            if not tool_calls:
                return {
                    "answer": assistant_message.get("content") or "Agent 未生成回答。",
                    "sources": sources,
                    "steps": steps,
                }
            messages.append(assistant_message)

            for tool_call in tool_calls:
                function = tool_call.get("function") or {}
                name = str(function.get("name") or "")
                arguments = _parse_arguments(function.get("arguments"))

                key = (name, json.dumps(arguments, ensure_ascii=False, sort_keys=True))
                cached = executed.get(key)
                if cached is None:
                    status, content = run_tool(context, name, arguments)
                    if status.status != "error":
                        # 只缓存成功结果：报错可能是瞬时故障，模型重试同一调用时值得再试一次
                        executed[key] = (status, content)
                else:
                    status, content = cached

                # 只有检索结果算证据。文档清单/目录是导航信息，并进 sources 会让前端
                # 把一份跟问题无关的文档清单显示成引用来源。
                if name == "knowledge_search" and isinstance(status.payload, dict):
                    sources.extend(
                        item for item in status.payload.get("results", []) if item not in sources
                    )

                steps.append({
                    "tool": name or "unknown",
                    "query": describe_call(name, arguments),
                    "result_count": status.count,
                })
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.get("id") or f"call-{len(steps)}",
                    "name": name or "unknown",
                    "content": content,
                })
    except Exception as exception:
        logger.exception("knowledge agent tool calling failed; falling back to retrieval answer")
        return _degraded_result(
            knowledge_base_id,
            question,
            history,
            reason=f"工具调用失败（{exception.__class__.__name__}）",
            steps=steps,
        )

    return _finalize(knowledge_base_id, messages, sources, steps)


def _finalize(
    knowledge_base_id: int,
    messages: list[dict[str, object]],
    sources: list[dict[str, object]],
    steps: list[dict[str, object]],
) -> dict[str, object]:
    """工具轮数用尽后的收尾：再发一次**不带工具**的请求，逼模型就地作答。

    为什么值得多花这一次调用：走到这里时对话里已经攒下了模型查回来的全部资料，
    直接返回「达到最大轮数」等于把它们全丢掉，用户看到的是一句内部错误 —— 而它本来
    完全能给出带引用的回答。实测里模型是很爱探索的（一次提问能连着发四个工具调用），
    这个分支一点都不罕见。

    比单纯调大 ``agent_max_rounds`` 好在哪：调大只是把问题推后，探索型模型照样能烧完；
    而不带工具的这一轮是**结构性保证** —— 没有了工具，它只有「用现有资料作答」这一条路。
    """
    try:
        response = _chat_completion(messages, None)
        answer = response["choices"][0]["message"].get("content")
        if answer:
            logger.info("agent answered after tool budget exhausted (knowledge base %s)", knowledge_base_id)
            return {"answer": answer, "sources": sources, "steps": steps}
    except Exception:
        logger.warning("final answer after tool budget exhausted failed", exc_info=True)

    return {
        "answer": "Agent 达到最大工具调用次数，请缩小问题范围后重试。",
        "sources": sources,
        "steps": steps,
    }


def _parse_arguments(raw: object) -> dict:
    """解析模型给出的工具参数。

    三重兜底，每一重都对应一种真实见过的模型行为：参数不是合法 JSON（流式拼接被截断）、
    是合法 JSON 但不是对象（给了个字符串或数组）、是对象但类型不对（由各工具自己收敛）。
    """
    try:
        arguments = json.loads(raw or "{}")
    except (json.JSONDecodeError, TypeError):
        return {}
    return arguments if isinstance(arguments, dict) else {}


def _degraded_result(
    knowledge_base_id: int,
    question: str,
    history: list[dict[str, str]] | None,
    reason: str,
    steps: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    """降级路径：不走工具，直接用「检索 + 模板回答」给出结果。

    ``answer_question`` 在整个模块里**只用在这里**。正常路径下最终答案必须来自模型正文，
    否则工具调用顺序会悄悄变成影响答案的隐藏变量。

    ``steps`` 是降级前**真实执行过**的工具步骤，必须原样保留：曾经这里会把它整个替换成
    一条伪造的 ``knowledge_search``，于是「先列文档、再超时降级」的轨迹看起来像「检索成功」——
    排查故障时等于把最关键的证据抹掉了。降级本身另起一条 ``fallback`` 步骤如实记录。

    ``sources`` 只用降级检索自己的结果：最终回答由 ``answer_question`` 生成，
    其中的 [1]、[2] 编号只对那一批来源成立，混入更早的来源会让编号错位。
    """
    logger.info("knowledge agent degraded: %s", reason)
    fallback = answer_question(knowledge_base_id, question, history)
    trace = list(steps or [])
    trace.append({
        "tool": "fallback",
        "query": f"降级：{reason}，改为检索直答",
        "result_count": len(fallback["sources"]),
    })
    return {
        "answer": fallback["answer"],
        "sources": fallback["sources"],
        "steps": trace,
    }


def _chat_completion(messages: list[dict[str, object]], tools: list[dict[str, object]] | None) -> dict[str, object]:
    """调用兼容 OpenAI 协议的 chat 接口。

    ``tools`` 传空（``None`` 或 ``[]``）表示这次不带工具 —— 用于需要「纯生成」的场合。
    """
    payload: dict[str, object] = {
        "model": settings.chat_model,
        "messages": messages,
        "temperature": 0.2,
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    request = Request(
        f"{settings.chat_base_url.rstrip('/')}/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": f"Bearer {settings.chat_api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=90) as response:
        return json.load(response)
