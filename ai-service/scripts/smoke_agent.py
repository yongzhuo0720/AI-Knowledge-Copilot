"""Agent 冒烟测试：真实调用大模型，端到端验证工具扩展可用。

用法（在 ``ai-service`` 容器内运行）：

    python /tmp/smoke_agent.py --knowledge-base-id 31

    # 不知道知识库 id 时，让它自己扫一遍
    python /tmp/smoke_agent.py --scan-max 50

## 为什么不能只靠单元测试

单元测试把 ``_chat_completion`` 换成了预置响应，验证的是**我们这边的循环逻辑**。而工具扩展
最容易出问题的地方恰好在这一层之外：

- 模型的工具选择行为 —— 描述写得含不含糊，模型会不会把 ``list_documents`` 用在事实问答上；
- 工具参数的真实形态 —— 模型可能给出 ``{"document": "guide.md"}`` 这种「看起来合理但
  不是 object_key」的值，或者把 limit 写成字符串；
- 工具结果能不能被模型消化 —— 回灌的 JSON 结构太绕，模型会忽略它转而凭记忆作答。

这些只有真跑一次才看得见，所以本脚本刻意**每个工具都走一遍**，再跑两轮完整问答。

## 安全性

三个工具全是只读的，不改任何数据，可以随时重跑。这也是这个脚本能进 CI/日常回归的前提。
"""
from __future__ import annotations

import argparse
import json
import sys


def _print(title: str, payload: object) -> None:
    print(f"\n{'=' * 72}\n{title}\n{'=' * 72}")
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))


def _pick_knowledge_base(max_id: int) -> int:
    """扫描一遍，找出第一个真的有索引内容的知识库。"""
    from app.indexing import count_chunks

    for candidate in range(1, max_id + 1):
        if count_chunks([candidate]) > 0:
            print(f"扫描到有内容的知识库：{candidate}")
            return candidate
    raise SystemExit(f"1..{max_id} 里没有任何已建索引的知识库，先上传并解析一份文档再跑")


def main() -> int:
    parser = argparse.ArgumentParser(description="Agent 工具扩展冒烟测试")
    parser.add_argument("--knowledge-base-id", type=int, default=0)
    parser.add_argument("--scan-max", type=int, default=50)
    arguments = parser.parse_args()

    from app import agent as agent_module
    from app.agent_tools import AgentToolContext, run_tool
    from app.settings import settings

    if not settings.chat_api_key:
        print("⚠️  未配置 chat_api_key，Agent 会走降级路径，测不到工具调用。", file=sys.stderr)
        return 2

    knowledge_base_id = arguments.knowledge_base_id or _pick_knowledge_base(arguments.scan_max)
    context = AgentToolContext(knowledge_base_id=knowledge_base_id)

    # ---- 1. 逐个工具直调：确认工具本身能跑通 ---------------------------------- #
    inventory = run_tool(context, "list_documents", {})
    _print("工具 1/3 · list_documents", {"status": inventory[0].status, "count": inventory[0].count})

    documents = inventory[0].payload.get("documents", []) if isinstance(inventory[0].payload, dict) else []
    if not documents:
        print("知识库里没有文档，后续工具无法验证", file=sys.stderr)
        return 1
    document = documents[0]["document"]
    print(f"\n清单里的第一篇：{documents[0]['name']}  ({document})")
    for entry in documents:
        print(f"  - {entry['name']}  {entry['chunks']} 片  预览={entry['preview']}")

    outline = run_tool(context, "document_outline", {"document": document, "limit": 5})
    _print(f"工具 2/3 · document_outline({document})", outline[0].payload)

    search = run_tool(context, "knowledge_search", {"query": "流程", "limit": 3, "document": document})
    _print("工具 3/3 · knowledge_search（限定文档）", {
        "status": search[0].status,
        "count": search[0].count,
        "first_hit": (search[0].payload.get("results") or [None])[0] if isinstance(search[0].payload, dict) else None,
    })

    # ---- 2. 越权防线：别的知识库的 key 必须被拒 ------------------------------- #
    denied = run_tool(context, "document_outline", {"document": "kb/999999/not-mine/secret.md"})
    _print("越权检查 · document_outline(其它知识库的 key)", {"status": denied[0].status, "payload": denied[0].payload})

    # ---- 3. 完整 Agent 问答 --------------------------------------------------- #
    questions = [
        # 这题靠检索答不出来 —— 没有「枚举」语义，模型必须选 list_documents
        "这个知识库一共收录了哪些文档？只列文件名。",
        # 这题必须走检索
        "这些文档里讲了什么内容？挑一个具体点说明，并标注引用编号。",
    ]
    for question in questions:
        result = agent_module.run_knowledge_agent(knowledge_base_id, question, [])
        _print(f"Agent 问答 · {question}", {
            "steps": result["steps"],
            "source_count": len(result["sources"]),
            "answer": result["answer"],
        })

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
