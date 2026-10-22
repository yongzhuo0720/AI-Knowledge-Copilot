"""检索效果评估：对比 vector / keyword / hybrid / rewrite 四种模式的 Recall@k 与 MRR。
    （rewrite = 混合检索 + 查询改写 / Multi-Query）

用法（在 ai-service 容器内，或任何能连上 Milvus 的环境）：

    AI_SERVICE_EVAL_KB_ID=31 python scripts/evaluate_retrieval.py

    # 换一份题目（例如新格式语料那一集）
    AI_SERVICE_EVAL_KB_ID=40 AI_SERVICE_EVAL_QUESTIONS=/app/eval/questions-new-formats.json \
        python scripts/evaluate_retrieval.py

## 为什么必须做量化评估

「混合检索更好」如果只是主观感觉，那它就不是工程结论。只有把同一批问题在几路召回下
各跑一遍、给出 Recall@k 和 MRR 的对比数字，才能证明改动有效，也才能在下一次调整
BM25 参数或候选池大小时判断是变好还是变坏。查询改写（Multi-Query）同样如此 ——
它要为每次提问多打一次模型，**没有数字支撑就不该打开**。

## 判分粒度：chunk 级，不是文档级

评估集为每条问题给出一个 ``answer_span`` —— 必须出现在正确片段里的字面串
（例如错误码 "1451"），可以是单个串，也可以是**一串必须同时出现**的字面串。

为什么不按「命中了哪篇文档」判分：一篇长目录会被切成多块，块与块之间高度相似。
回答「1451 是什么原因」时把写着 1366 的那一块捞上来，文档是命中的，答案是错的。
文档级指标会把这判成成功，完全掩盖问题。答案必须落到片段上。

为什么编号之外还要带上「答案文本」：只认编号同样会虚高。

- 一篇只罗列型号的目录片段含 ``XZ-2500``，但并没有回答「默认限流参数是多少」；
- 错误码 ``1836`` 会同时出现在完整条目和被切片截断的残句里，只认编号时
  「残句」也算命中，等于把「切坏了」误判成「检索对了」。

所以精确编号题一律用「编号 + 答案文本」的双条件，两个都出现在同一片段才算命中。

## 两个指标

Recall@k —— top-k 里有没有正确片段。衡量「捞不捞得到」，对应召回能力。
MRR      —— 第一个正确片段的排名倒数均值。衡量「排得够不够前」，对应排序能力。
只看 Recall 会漏掉排序退化（正确结果从第 1 掉到第 5，Recall 不变但体验变差），
只看 MRR 又会漏掉完全没召回的情况，所以两个都要看。
"""

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.eval_judging import rank_of_first_match as _rank_of_first_match  # noqa: E402
from app.eval_judging import span_alternatives as _span_alternatives  # noqa: E402
from app.eval_judging import summarize as _summarize  # noqa: E402
from app.hybrid_search import hybrid_retrieve, multi_query_retrieve  # noqa: E402
from app.query_rewrite import rewrite_query  # noqa: E402

# ``rewrite`` 是「hybrid + 查询改写」这一档。把它并进 MODES 而不是另开一张表，
# 是为了让「改写到底有没有带来提升」和既有的三档在**同一批题、同一套判分**下直接可比。
MODES = ("vector", "keyword", "hybrid", "rewrite")
DEFAULT_QUESTIONS = Path(__file__).resolve().parents[2] / "docs" / "rag-eval" / "questions.json"

# 判分口径（_span_alternatives / _rank_of_first_match / _summarize）统一放在
# app/eval_judging.py，与 scripts/verify_index.py 共用同一份实现 —— 如果两边各写一份，
# 只要有一边改了口径，就会出现「评估说 MISS、自检说可答」这种自相矛盾的结果，
# 而自检的意义恰恰是给评估提供独立但同口径的证据。
# 这里保留原来的私有名字，是为了不打断既有调用方与单元测试。


def evaluate(
    questions: list[dict], knowledge_base_id: int, top_k: int
) -> tuple[dict[str, dict[str, float]], dict[str, dict[str, dict[str, float]]], list[dict], list[dict]]:
    raw: dict[str, list[dict]] = {mode: [] for mode in MODES}
    per_question: list[dict] = []
    rewrites_log: list[dict] = []

    for item in questions:
        row: dict[str, object] = {"question": item["question"], "kind": item.get("kind", "semantic")}
        rewrites: list[str] = []
        for mode in MODES:
            if mode == "rewrite":
                # 显式改写再传入，而不是让 multi_query_retrieve 内部自己改：
                # 这样评估脚本能把自己用到的那几条改写记下来存档。改写是按温度 0.8 生成的、
                # 不可复现，不存档的话「模型当时改写成什么样」这个关键输入就丢了，
                # 数字既无法解释也无法回看。走的仍然是生产那一个 rewrite_query。
                rewrites = rewrite_query(item["question"])
                hits = multi_query_retrieve(knowledge_base_id, item["question"], top_k, "hybrid", rewrites=rewrites)
            else:
                hits = hybrid_retrieve(knowledge_base_id, item["question"], top_k, mode)
            rank = _rank_of_first_match(hits, _span_alternatives(item))
            raw[mode].append({"rank": rank, "kind": row["kind"]})
            row[mode] = rank
        rewrites_log.append({"question": item["question"], "rewrites": rewrites})
        per_question.append(row)

    overall = {mode: _summarize(raw[mode]) for mode in MODES}

    by_kind: dict[str, dict[str, dict[str, float]]] = {}
    for kind in sorted({item["kind"] for item in per_question}):
        by_kind[kind] = {
            mode: _summarize([entry for entry in raw[mode] if entry["kind"] == kind])
            for mode in MODES
        }
    return overall, by_kind, per_question, rewrites_log


def _display_width(text: str) -> int:
    """中文字符占两个显示宽度，用它算对齐，否则表格会歪。"""
    return sum(2 if ord(char) > 0x2E80 else 1 for char in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _display_width(text))


def _print_metrics(title: str, metrics: dict[str, dict[str, float]], top_k: int) -> None:
    print(f"\n{title}")
    print("-" * (18 + 11 * len(MODES)))
    header = _pad("", 18)
    for mode in MODES:
        header += f"{mode:>11}"
    print(header)
    for label, key in ((f"Recall@{top_k}", "recall"), ("MRR", "mrr"), ("Hit@1", "hit1"), ("题数", "total")):
        line = _pad(label, 18)
        for mode in MODES:
            value = metrics[mode][key]
            line += f"{value * 100:>10.1f}%" if key != "total" else f"{value:>11.0f}"
        print(line)


def _better(candidate: int | None, baseline: int | None) -> bool:
    """名次是否比基线更好。``None``（完全没召回）等同最差。"""
    if candidate is None:
        return False
    return baseline is None or candidate < baseline


def _rank_text(rank: int | None) -> str:
    return "MISS" if rank is None else f"#{rank}"


def main() -> None:
    knowledge_base_id = int(os.environ.get("AI_SERVICE_EVAL_KB_ID", "31"))
    questions_path = Path(os.environ.get("AI_SERVICE_EVAL_QUESTIONS", str(DEFAULT_QUESTIONS)))
    top_k = int(os.environ.get("AI_SERVICE_EVAL_TOP_K", "5"))
    rewrites_path = os.environ.get("AI_SERVICE_EVAL_REWRITES_OUT", "")

    payload = json.loads(questions_path.read_text(encoding="utf-8"))
    questions = payload["questions"]

    print(f"评估集：{payload.get('name', questions_path.name)}")
    print(f"知识库：{knowledge_base_id}    题目数：{len(questions)}    Top-K：{top_k}")
    print("判分方式：chunk 级 —— 同一片段需命中 answer_span 的全部字面串，或 answer_any 的任一备选")
    print("模式说明：vector 纯向量 / keyword 纯 BM25 / hybrid 混合检索 / rewrite 混合检索 + 查询改写")

    overall, by_kind, per_question, rewrites_log = evaluate(questions, knowledge_base_id, top_k)

    # ---- 逐题明细 ----
    width = max(_display_width(str(item["question"])) for item in per_question)
    width = max(width, _display_width("问题"))
    print()
    header = _pad("问题", width) + _pad("类型", 12)
    for mode in MODES:
        header += f"{mode:>9}"
    print(header)
    print("-" * (width + 12 + 9 * len(MODES)))
    for item in per_question:
        line = _pad(str(item["question"]), width) + _pad(str(item["kind"]), 12)
        for mode in MODES:
            rank = item[mode]
            line += f"{('MISS' if rank is None else '#' + str(rank)):>9}"
        print(line)

    _print_metrics("总体指标", overall, top_k)

    kind_titles = {
        "exact-code": "分组：含编号 / 型号的查询（向量检索的弱项）",
        "semantic": "分组：纯语义提问（向量检索的强项）",
        "table": "分组：表格类语料（xlsx / csv，行级块）",
        "webpage": "分组：网页语料（html）",
        "slide": "分组：幻灯片语料（pptx）",
    }
    for kind, metrics in by_kind.items():
        _print_metrics(kind_titles.get(kind, f"分组：{kind}"), metrics, top_k)

    best = max(MODES, key=lambda mode: (overall[mode]["recall"], overall[mode]["mrr"]))
    print("\n综合最优模式：" + best)

    # ---- 查询改写：逐题对比 hybrid 与 rewrite ----
    # 改写是唯一一个「每次提问都要多打一次模型」的改动，所以最该看清它到底改了什么。
    print("\n查询改写对比（hybrid → rewrite）：")
    improved = [item for item in per_question if _better(item["rewrite"], item["hybrid"])]
    worsened = [item for item in per_question if _better(item["hybrid"], item["rewrite"])]
    same = [item for item in per_question if item["rewrite"] == item["hybrid"]]
    print(f"  变好 {len(improved)} 题 / 变差 {len(worsened)} 题 / 不变 {len(same)} 题")
    for label, items in (("变好", improved), ("变差", worsened)):
        for item in items:
            print(f"  [{label}] {item['question']}  hybrid={_rank_text(item['hybrid'])} → rewrite={_rank_text(item['rewrite'])}")

    # ---- 诊断：把 hybrid 没能排到第一的题挑出来 ----
    # 这类题才是真正值得看的：Recall 全中时，唯一能区分的信号就是名次。
    imperfect = [item for item in per_question if item["hybrid"] != 1]
    if imperfect:
        print(f"\nhybrid 未排第一的题目（{len(imperfect)} 题）：")
        for item in imperfect:
            rank = item["hybrid"]
            reason = "完全没召回" if rank is None else f"排在 #{rank}，属于排序问题"
            print(f"  - {item['question']}  →  {reason}")
    else:
        print("\nhybrid 在全部题目上都排到了第一位。")

    # ---- 改写存档 ----
    # 改写按温度 0.8 生成、不可复现，不落盘的话事后无法解释「这个数字是怎么来的」。
    if rewrites_path:
        Path(rewrites_path).write_text(
            json.dumps({"questions": rewrites_log}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"\n改写存档已写入：{rewrites_path}")


if __name__ == "__main__":
    main()
