"""索引自检：确认知识库索引「可信」，然后才谈指标。

用法：

    # 只检查（不动数据，安全，可随时跑）
    python scripts/verify_index.py --knowledge-base-id 31

    # 先按当前切片策略重建索引，再检查
    python scripts/verify_index.py --knowledge-base-id 31 --reindex \\
        --questions /docs/rag-eval/questions.json

## 为什么需要这个脚本

评估指标只有在**索引可信**的前提下才有意义。本项目的评估过程中真出现过「同一份代码、
同一份评估集，连续跑出三组互相矛盾的数字」，最初被误判为 Milvus 主键复用竞态，
实际原因是容器里同时跑着两个重索引进程（一个删旧 chunk、一个写新 chunk），评估读到的
一直是中间态。这类故障不会报错，只会让指标悄悄失真，所以必须有一道显式的验收。

本脚本给出两条**互相独立**的证据：

1. **稳定性** —— 连测多次 chunk 总数与逐文档分布，必须完全一致。证明索引「没在变」。
2. **答案可用性** —— 每道题的判分串与库里全部 chunk 做**纯字面匹配**（不经过向量、
   不经过 BM25），必须 0 缺失。证明索引「答案真的在里面」。

第二条比第一条更强：计数一致只说明稳定，字面命中才说明内容对。只有一条通过时，
「指标异常」的病因就已经被定位了 ——
稳定性不过关 → 有并发写者；答案缺失 → 语料或入库有问题，都不是检索器的锅。

## 为什么重索引必须走这条路，而不是提交 HTTP 任务

``replace_existing=True`` 的实现是「先删旧 chunk，再重新解析入库」，删除与写入之间
存在窗口期。通过 HTTP 提交后台任务再轮询，一旦轮询超时就放行，统计和评估都会落在
这个窗口期里，把文档记成 0 个 chunk。所以这里改为**同进程同步调用**
``process_parse_task`` —— 它本身就是阻塞的，函数返回即代表该文档已完成删旧 + 写入 +
flush。

另外，``--reindex`` 会先清掉表里非 ``COMPLETED`` 的残留任务：服务的
``_recover_tasks_loop`` 是**每几秒轮询一次**的循环（不是只在启动时跑一次），
会反复把 ``ACCEPTED`` / ``RETRYING`` / 超时 ``PROCESSING`` 的任务捞出来重跑。
不清掉的话，它们会在做稳定性观测时插进来改索引。

部署提醒：本脚本依赖它能连上 Milvus / MySQL / MinIO，请在 ``ai-service`` 容器内运行。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.eval_judging import matched_candidates, span_alternatives  # noqa: E402
from app.indexing import COLLECTION_NAME  # noqa: E402
from app.settings import settings  # noqa: E402

CONTENT_TYPES = {
    ".md": "text/markdown",
    ".markdown": "text/markdown",
    ".txt": "text/plain",
    ".csv": "text/csv",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".html": "text/html",
    ".htm": "text/html",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".pdf": "application/pdf",
}


def _milvus_collection():
    from pymilvus import Collection, connections

    connections.connect(
        alias="default",
        host=settings.milvus_host,
        port=str(settings.milvus_port),
        timeout=30,
    )
    collection = Collection(COLLECTION_NAME)
    collection.load()
    return collection


def fetch_chunks(knowledge_base_id: int) -> list[dict]:
    """取出该知识库的全部 chunk（object_key + content）。"""
    collection = _milvus_collection()
    return collection.query(
        expr=f"knowledge_base_id == {knowledge_base_id}",
        output_fields=["object_key", "content"],
        limit=16384,
    )


def chunk_counts(knowledge_base_id: int) -> Counter:
    return Counter(str(row["object_key"]).rsplit("/", 1)[-1] for row in fetch_chunks(knowledge_base_id))


def minio_objects() -> list[tuple[str, int]]:
    from minio import Minio

    client = Minio(
        settings.minio_endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        secure=False,
    )
    return [(item.object_name, item.size or 0) for item in client.list_objects(settings.minio_bucket, recursive=True)]


def check_stability(knowledge_base_id: int, times: int, interval: float) -> tuple[bool, list[Counter]]:
    snapshots: list[Counter] = []
    for index in range(times):
        if index:
            time.sleep(interval)
        snapshot = chunk_counts(knowledge_base_id)
        snapshots.append(snapshot)
        print(f"  第 {index + 1}/{times} 次：{sum(snapshot.values())} 个 chunk / {len(snapshot)} 篇文档")
    return all(snapshot == snapshots[0] for snapshot in snapshots), snapshots


def check_answers(payload: dict, chunks: list[dict]) -> list[dict]:
    """每道题的判分串必须能在库里字面命中，否则这题必然 MISS。

    命中判定直接复用 ``app.eval_judging``，与评估脚本是同一份实现 —— 两条证据
    必须同口径，否则「评估 MISS、自检可答」这种矛盾结论无从解释。
    """
    absent: list[dict] = []
    for item in payload["questions"]:
        alternatives = span_alternatives(item)
        matching = matched_candidates(chunks, alternatives)
        if matching:
            # 同一篇文档可能有多个片段命中，按文档名去重后再展示
            holders = sorted({
                str(row["object_key"]).rsplit("/", 1)[-1] for row in matching
            })
            print(f"  OK   {item['question'][:32]:34s} {', '.join(holders)}")
        else:
            print(f"  MISS {item['question'][:32]:34s} 库中无答案")
            absent.append({
                "question": item["question"],
                "alternatives": alternatives,
                "expect_doc": item.get("expect_doc", ""),
            })
    return absent


def clean_unfinished_tasks(knowledge_base_id: int) -> int:
    """清掉**本知识库**非 COMPLETED 的残留任务，避免周期性恢复循环在做观测时改索引。

    必须按知识库过滤：这行 DELETE 是真删任务行，误删其他知识库的 ACCEPTED/RETRYING
    任务，那些文档就会永远停在中途状态 —— 而调用方的观测目的只覆盖一个知识库。
    """
    import pymysql

    connection = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM document_processing_task WHERE status <> 'COMPLETED' AND knowledge_base_id = %s",
                (knowledge_base_id,),
            )
            return cursor.rowcount
    finally:
        connection.close()


def reindex(knowledge_base_id: int, object_keys: list[str]) -> list[str]:
    """同步重建索引：返回即代表该文档已完成删旧 + 写入 + flush。"""
    from app.processing import get_parse_task, process_parse_task, submit_parse_task
    from app.schemas import ParseDocumentRequest

    failures: list[str] = []
    for key in object_keys:
        name = key.rsplit("/", 1)[-1]
        suffix = "." + name.rsplit(".", 1)[-1].lower() if "." in name else ""
        request = ParseDocumentRequest(
            object_key=key,
            filename=name,
            content_type=CONTENT_TYPES.get(suffix, "text/plain"),
            knowledge_base_id=knowledge_base_id,
            replace_existing=True,
        )
        task = submit_parse_task(request)
        try:
            process_parse_task(task.task_id, request)
        except Exception as exception:  # noqa: BLE001
            failures.append(f"{name}: {exception}")
        final = get_parse_task(task.task_id)
        status = final.status if final is not None else "MISSING"
        reason = (final.failure_reason or "") if final is not None else ""
        if status != "COMPLETED":
            failures.append(f"{name}: {status} {reason}")
        print(f"  {status:10s} {name}  {reason[:100]}")
    return failures


def is_reliable(stable: bool, failures: list, absent: list, missing_docs: list) -> bool:
    """四条信号缺一不可。

    「稳定」只说明索引没在变，「答案可用」才说明内容对；「无失败」「无缺失文档」
    则排除了「有的文档压根没入库」这种情况。任何一条不过关，指标都没有解读价值。
    """
    return bool(stable and not failures and not absent and not missing_docs)


def _targets(payload: dict) -> tuple[list[str], list[str]]:
    """按评估集声明的语料名，在对象存储里找回各自的 object_key。"""
    declared = payload.get("corpus") or []
    by_name = {name.rsplit("/", 1)[-1]: name for name, _ in minio_objects()}
    keys = [by_name[name] for name in declared if name in by_name]
    missing = [name for name in declared if name not in by_name]
    return keys, missing


def main() -> int:
    parser = argparse.ArgumentParser(description="索引自检：稳定性 + 答案可用性")
    parser.add_argument("--knowledge-base-id", type=int, required=True)
    parser.add_argument("--questions", type=Path, default=None, help="评估集路径，用于答案可用性检查")
    parser.add_argument("--reindex", action="store_true", help="先按当前切片策略同步重建索引")
    parser.add_argument("--times", type=int, default=3, help="稳定性连测次数")
    parser.add_argument("--interval", type=float, default=7.0, help="稳定性连测间隔（秒）")
    parser.add_argument("--report", type=Path, default=None, help="把结果写入 JSON")
    args = parser.parse_args()

    knowledge_base_id = args.knowledge_base_id

    payload = None
    if args.questions is not None:
        payload = json.loads(args.questions.read_text(encoding="utf-8"))

    failures: list[str] = []
    missing_docs: list[str] = []

    if args.reindex:
        if payload is None:
            print("--reindex 需要同时提供 --questions（用来确定该重建哪些文档）")
            return 2
        removed = clean_unfinished_tasks(knowledge_base_id)
        print(f"清理非 COMPLETED 残留任务：{removed} 条")
        keys, missing_docs = _targets(payload)
        print(f"\n评估集声明 {len(payload.get('corpus') or [])} 篇，对象存储中找到 {len(keys)} 篇")
        for name in missing_docs:
            print(f"    [对象存储里没有] {name}")
        print("\n同步重索引（返回即代表已完成删旧 + 写入 + flush）：")
        failures = reindex(knowledge_base_id, keys)

    print(f"\n稳定性观测（连测 {args.times} 次，间隔 {args.interval:g} 秒）：")
    stable, snapshots = check_stability(knowledge_base_id, args.times, args.interval)
    print(f"多次一致：{'是' if stable else '否 <<< 仍有写者在改索引，指标不可信'}")

    latest = snapshots[-1]
    print(f"\n{'文档':34s}{'chunk':>6}")
    for name in sorted(latest):
        print(f"  {name:34s}{latest[name]:>6}")

    absent: list[dict] = []
    if payload is not None:
        chunks = fetch_chunks(knowledge_base_id)
        print("\n逐题反向验证（纯字面匹配，不经过任何检索）：")
        absent = check_answers(payload, chunks)
        print(f"\n结论：{len(payload['questions'])} 道题中 {len(absent)} 道的答案不在库里")
        for item in absent:
            print(f"  - {item['question']}  期望={item['expect_doc']}  判分串={item['alternatives']}")

    reliable = is_reliable(stable, failures, absent, missing_docs)
    print(f"\n索引可信：{'是' if reliable else '否'}")

    if args.report is not None:
        args.report.write_text(
            json.dumps(
                {
                    "knowledge_base_id": knowledge_base_id,
                    "chunk_counts": dict(latest),
                    "total_chunks": sum(latest.values()),
                    "stable": stable,
                    "failures": failures,
                    "missing_docs": missing_docs,
                    "absent_answers": absent,
                    "reliable": reliable,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"报告已写入 {args.report}")

    return 0 if reliable else 1


if __name__ == "__main__":
    raise SystemExit(main())
