"""离线核对评估题的判分串：**不连 Milvus、不连网络**，只回答一个问题 ——
「按当前的切片策略，这条判分串真的能在语料里被字面命中吗？」

用法：

    # 在 ai-service 目录下
    python scripts/verify_question_spans.py --questions ../docs/rag-eval/questions-paraphrase.json

    # 语料目录不是默认位置时显式指定
    python scripts/verify_question_spans.py --questions q.json --corpus-dir /path/to/corpus

## 为什么要有这个脚本（它和 verify_index.py 不重复）

``verify_index.py`` 检查的是「**已入库**的 chunk 里有没有答案」——它要连 Milvus，
所以在**写题目的时候**用不上：新写的题还没上传，跑不了。

而写题目时最常犯的错恰好是**判分串本身写错了**：语料写的是「不必回到主表取数据」，
题目里手写成「不用回主表」—— 一个字之差，评估就永远是 MISS。这时如果直接去跑评估，
你看到的是一个「检索失败」，然后会去排查检索器、BM25 参数、向量维度……
**排查方向从第一步就错了。**

这个脚本把这一步提前到离线、秒级、零成本，并在输出里顺带核对
``expect_doc``（这道题的答案到底落在哪篇文档里）—— 写错文档归属同样会让判分口径失真。

它复用的是**评估本身那套匹配函数**（``app.eval_judging.matched_candidates``），
所以「离线说能命中」与「评估说命中」是同一套口径，不会自相矛盾。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.chunking import chunk_document  # noqa: E402
from app.eval_judging import matched_candidates, span_alternatives  # noqa: E402
from app.settings import settings  # noqa: E402

DEFAULT_CORPUS = Path(__file__).resolve().parents[2] / "docs" / "rag-eval" / "corpus"

CONTENT_TYPES = {
    ".md": "text/markdown",
    ".csv": "text/csv",
    ".html": "text/html",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
}


def build_chunks(corpus_dir: Path, filenames: list[str]) -> dict[str, list[dict]]:
    """按**生产同一套参数**切片，返回 {文件名: [{"content": ...}]}。

    参数必须读 ``settings`` 而不是写死 800/120：改了 chunk_size 之后
    「离线说能命中、评估说不能」这种自相矛盾，根因就是两处参数不一致。
    """
    per_file: dict[str, list[dict]] = {}
    for name in filenames:
        path = corpus_dir / name
        if not path.exists():
            raise SystemExit(f"语料文件不存在：{path}")
        chunks = chunk_document(
            path.read_bytes(),
            CONTENT_TYPES.get(path.suffix.lower(), ""),
            name,
            settings.chunk_size,
            settings.chunk_overlap,
        )
        per_file[name] = [{"content": chunk} for chunk in chunks]
    return per_file


def main() -> int:
    parser = argparse.ArgumentParser(description="离线核对评估题的判分串")
    parser.add_argument("--questions", required=True, help="题目 JSON 路径")
    parser.add_argument("--corpus-dir", default=str(DEFAULT_CORPUS))
    arguments = parser.parse_args()

    corpus_dir = Path(arguments.corpus_dir)
    payload = json.loads(Path(arguments.questions).read_text(encoding="utf-8"))
    filenames = payload.get("corpus") or sorted(item.name for item in corpus_dir.iterdir())
    per_file = build_chunks(corpus_dir, filenames)

    total_chunks = sum(len(chunks) for chunks in per_file.values())
    print(f"评估集：{payload.get('name', arguments.questions)}")
    print(f"语料：{len(filenames)} 篇 → {total_chunks} 个 chunk（chunk_size={settings.chunk_size}, overlap={settings.chunk_overlap}）")

    failures: list[str] = []
    wrong_document: list[str] = []
    for item in payload["questions"]:
        question = item["question"]
        alternatives = span_alternatives(item)
        if not alternatives:
            failures.append(f"{question}（没有 answer_span / answer_any，判分永远不会命中）")
            print(f"  [FAIL] {question} —— 缺少判分依据")
            continue

        hits_by_file = {
            name: matched_candidates(chunks, alternatives)
            for name, chunks in per_file.items()
        }
        matched_files = [name for name, hits in hits_by_file.items() if hits]

        if not matched_files:
            failures.append(question)
            print(f"  [MISS] {question}")
            print(f"         判分串 {alternatives} 在全部 {total_chunks} 个 chunk 里都找不到字面命中")
            continue

        expect = str(item.get("expect_doc") or "")
        mark = "OK  "
        detail = "、".join(matched_files)
        if expect and not any(expected in name for name in matched_files for expected in expect.split(" 或 ")):
            # expect_doc 允许写成「a.md 或 b.md」，逐个别名比对
            wrong_document.append(question)
            mark = "DOC?"
            detail += f"   ← 与 expect_doc「{expect}」对不上"
        print(f"  [{mark}] {question}")
        print(f"         命中 {sum(len(hits_by_file[name]) for name in matched_files)} 片，落在：{detail}")

    print()
    if failures:
        print(f"判分串缺失 {len(failures)} 题 —— 这些题在没有修好之前，评估出的 MISS 不算检索的错：")
        for question in failures:
            print(f"  - {question}")
    if wrong_document:
        print(f"文档归属可疑 {len(wrong_document)} 题（不影响判分，但会让报告里的归因写错）：")
        for question in wrong_document:
            print(f"  - {question}")
    if not failures:
        print("全部题目的判分串都能在语料里字面命中。")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
