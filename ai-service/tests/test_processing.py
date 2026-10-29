import time
from dataclasses import replace
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.indexing import IndexCancelledError
from app.main import app
from app.processing import (
    _TaskHeartbeat,
    _heartbeat_interval,
    get_parse_task,
    process_parse_task,
    retry_parse_task,
    submit_parse_task,
)
from app.schemas import ParseDocumentRequest
from app.task_store import StoredTask


def test_create_parse_task() -> None:
    response = TestClient(app).post(
        "/api/v1/document-processing/tasks",
        json={
            "object_key": "kb/20/guide.pdf",
            "filename": "guide.pdf",
            "content_type": "application/pdf",
            "knowledge_base_id": 20,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "0"
    assert body["data"]["object_key"] == "kb/20/guide.pdf"
    assert body["data"]["status"] == "ACCEPTED"
    assert body["data"]["task_id"]

    status_response = TestClient(app).get(f"/api/v1/document-processing/tasks/{body['data']['task_id']}")
    assert status_response.status_code == 200
    assert status_response.json()["data"]["status"] == "FAILED"


def test_create_parse_task_accepts_camel_case_request() -> None:
    response = TestClient(app).post(
        "/api/v1/document-processing/tasks",
        json={
            "objectKey": "kb/20/guide.pdf",
            "filename": "guide.pdf",
            "contentType": "application/pdf",
            "knowledgeBaseId": 20,
        },
    )

    assert response.status_code == 200
    assert response.json()["data"]["object_key"] == "kb/20/guide.pdf"


def test_processing_task_completes_after_extracting_and_indexing(monkeypatch) -> None:
    request = ParseDocumentRequest(
        object_key="kb/20/guide.txt", filename="guide.txt", content_type="text/plain", knowledge_base_id=20
    )
    task = submit_parse_task(request)
    monkeypatch.setattr("app.processing.download_object", lambda _: "团队知识库内容".encode())
    monkeypatch.setattr("app.processing.index_chunks", lambda *args, **kwargs: None)

    process_parse_task(task.task_id, request)

    assert get_parse_task(task.task_id).status == "COMPLETED"


def test_processing_task_persists_failure_reason_and_retry_count_in_memory_fallback(monkeypatch) -> None:
    request = ParseDocumentRequest(
        object_key="kb/20/guide.bin", filename="guide.bin", content_type="application/octet-stream", knowledge_base_id=20
    )
    monkeypatch.setattr("app.processing.settings.task_max_retries", 0)
    task = submit_parse_task(request)
    monkeypatch.setattr("app.processing.download_object", lambda _: b"content")

    process_parse_task(task.task_id, request)

    failed = get_parse_task(task.task_id)
    assert failed.status == "FAILED"
    assert failed.retry_count == 0
    assert "ValueError" in failed.failure_reason

    retried = retry_parse_task(task.task_id)
    assert retried.status == "ACCEPTED"
    assert retried.retry_count == 0


def test_cancelled_indexing_is_terminal_and_never_retried(monkeypatch) -> None:
    """文档在解析途中被删除：任务必须立即终止。

    走通用重试会把刚删掉的向量再写回去 —— 也就是「删除后复活」。
    """
    request = ParseDocumentRequest(
        object_key="kb/20/deleted.txt", filename="deleted.txt", content_type="text/plain", knowledge_base_id=20
    )
    monkeypatch.setattr("app.processing.settings.task_max_retries", 3)
    task = submit_parse_task(request)
    monkeypatch.setattr("app.processing.download_object", lambda _: "团队知识库内容".encode())

    attempts: list = []

    def cancelled_index(*args: object, **kwargs: object) -> None:
        attempts.append(args)
        raise IndexCancelledError("document kb/20/deleted.txt was deleted; indexing cancelled")

    monkeypatch.setattr("app.processing.index_chunks", cancelled_index)

    process_parse_task(task.task_id, request)

    final = get_parse_task(task.task_id)
    assert final.status == "FAILED"
    assert "IndexCancelledError" in final.failure_reason
    assert final.retry_count == 0, "取消不该计入重试次数"
    assert len(attempts) == 1, "取消之后仍然重试了，删除的向量会被写回来"


# --------------------------------------------------------------------------
# 心跳：让「正在处理的大文档」不被误判成僵尸任务
# --------------------------------------------------------------------------


def test_heartbeat_interval_keeps_a_fourfold_margin(monkeypatch) -> None:
    """心跳必须显著密于 stale 阈值，否则它自己就成了僵尸判定的一部分。"""
    monkeypatch.setattr("app.processing.settings.task_processing_stale_seconds", 300)
    assert _heartbeat_interval() == 75

    monkeypatch.setattr("app.processing.settings.task_processing_stale_seconds", 1000)
    assert _heartbeat_interval() == 250


def test_heartbeat_interval_never_goes_below_the_floor(monkeypatch) -> None:
    """阈值被调得很小时，心跳也不能密到变成负担。"""
    monkeypatch.setattr("app.processing.settings.task_processing_stale_seconds", 4)
    assert _heartbeat_interval() == 5


def test_heartbeat_reports_while_the_task_is_being_held(monkeypatch) -> None:
    beats: list[str] = []
    monkeypatch.setattr("app.processing.settings.task_store_enabled", True)
    monkeypatch.setattr("app.processing._heartbeat_interval", lambda: 0.01)
    monkeypatch.setattr("app.processing.task_store", SimpleNamespace(touch=beats.append))

    with _TaskHeartbeat("task-1"):
        deadline = time.monotonic() + 2
        while not beats and time.monotonic() < deadline:
            time.sleep(0.01)

    assert beats, "心跳从未上报到任务存储"
    assert set(beats) == {"task-1"}

    settled = len(beats)
    time.sleep(0.05)
    assert len(beats) == settled, "任务结束后心跳仍在继续"


def test_heartbeat_is_disabled_without_a_task_store(monkeypatch) -> None:
    """没开任务存储时不该平白起线程、更不该去连库。"""
    beats: list[str] = []
    monkeypatch.setattr("app.processing.settings.task_store_enabled", False)
    monkeypatch.setattr("app.processing._heartbeat_interval", lambda: 0.01)
    monkeypatch.setattr("app.processing.task_store", SimpleNamespace(touch=beats.append))

    with _TaskHeartbeat("task-1"):
        time.sleep(0.05)

    assert beats == []


class _InMemoryStore:
    """够用的内存版任务存储，只为让「解析 → 心跳」这条链路能脱离 MySQL 跑起来。"""

    def __init__(self) -> None:
        self.tasks: dict[str, StoredTask] = {}
        self.beats: list[str] = []

    def create(self, task: StoredTask) -> None:
        self.tasks[task.task_id] = task

    def get(self, task_id: str) -> StoredTask | None:
        return self.tasks.get(task_id)

    def claim(self, task_id: str) -> bool:
        task = self.tasks.get(task_id)
        if task is None or task.status not in {"ACCEPTED", "RETRYING", "PROCESSING"}:
            return False
        self.tasks[task_id] = replace(task, status="PROCESSING")
        return True

    def update(self, task_id: str, status: str, failure_reason: str | None, retry_count: int) -> StoredTask | None:
        task = self.tasks.get(task_id)
        if task is None:
            return None
        self.tasks[task_id] = replace(
            task, status=status, failure_reason=failure_reason, retry_count=retry_count
        )
        return self.tasks[task_id]

    def retry(self, task_id: str) -> StoredTask | None:
        return self.tasks.get(task_id)

    def touch(self, task_id: str) -> None:
        self.beats.append(task_id)

    def find_recoverable(self) -> list[StoredTask]:
        return []


def test_processing_keeps_the_task_fresh_through_a_slow_index(monkeypatch) -> None:
    """回归测试：解析耗时超过 stale 阈值时，任务必须持续被标记为「活着」。

    没有心跳时，恢复循环的 ``find_recoverable`` 会把这个正在处理的任务判成僵尸，
    然后**从头再做一遍** —— 重新下载、重新切片、重新调 embedding 接口。
    这份成本是静默发生的，只有文档大到超过阈值时才会暴露。
    """
    store = _InMemoryStore()
    monkeypatch.setattr("app.processing.task_store", store)
    monkeypatch.setattr("app.processing.settings.task_store_enabled", True)
    monkeypatch.setattr("app.processing._heartbeat_interval", lambda: 0.01)
    monkeypatch.setattr("app.processing.download_object", lambda _: "团队知识库内容".encode())

    indexed: list[object] = []

    def slow_index(*args: object, **kwargs: object) -> None:
        # 模拟「切片多、embedding 慢」：耗时远超心跳间隔。
        deadline = time.monotonic() + 0.15
        while time.monotonic() < deadline:
            time.sleep(0.01)
        indexed.append(args)

    monkeypatch.setattr("app.processing.index_chunks", slow_index)

    request = ParseDocumentRequest(
        object_key="kb/20/big.txt", filename="big.txt", content_type="text/plain", knowledge_base_id=20
    )
    task = submit_parse_task(request)

    process_parse_task(task.task_id, request)

    assert indexed, "index_chunks 根本没被调用"
    assert store.beats, "解析期间没有发出任何心跳"
    assert set(store.beats) == {task.task_id}
    assert store.get(task.task_id).status == "COMPLETED"


def test_reports_stage_progress_and_clears_it_on_terminal_state(monkeypatch) -> None:
    """进度随解析阶段推进、embedding 逐片上报、终态后清空。

    进度只活在内存里（合并进任务响应），前端靠它画进度条；
    终态清空是为了让界面切回按 status 展示，不读到过期的中间值。
    """
    store = _InMemoryStore()
    monkeypatch.setattr("app.processing.task_store", store)
    monkeypatch.setattr("app.processing.settings.task_store_enabled", True)
    monkeypatch.setattr("app.processing.download_object", lambda _: "团队知识库内容".encode())
    monkeypatch.setattr("app.processing.chunk_document", lambda *args, **kwargs: ["切片一", "切片二"])

    observed: list[tuple[object, object]] = []

    def fake_index(*args: object, on_progress=None, **kwargs: object) -> None:
        def snapshot() -> tuple[object, object]:
            current = get_parse_task(task.task_id)
            return (current.progress, current.stage)

        observed.append(snapshot())
        if on_progress is not None:
            on_progress(1, 2)
            observed.append(snapshot())
            on_progress(2, 2)
            observed.append(snapshot())

    monkeypatch.setattr("app.processing.index_chunks", fake_index)

    request = ParseDocumentRequest(
        object_key="kb/20/guide.txt", filename="guide.txt", content_type="text/plain", knowledge_base_id=20
    )
    task = submit_parse_task(request)
    process_parse_task(task.task_id, request)

    # 进入 index_chunks 前是 chunked(45)；embedding 随完成片数在 50-90 推进
    assert observed[0] == (45, "chunked")
    assert observed[1] == (70, "embedding")
    assert observed[2] == (90, "embedding")

    final = get_parse_task(task.task_id)
    assert final.status == "COMPLETED"
    assert final.progress is None and final.stage is None
