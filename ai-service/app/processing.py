import logging
import time
from threading import Event, Lock, Thread
from uuid import uuid4

from app.chunking import chunk_document
from app.indexing import index_chunks
from app.schemas import ParseDocumentRequest, ParseTaskResponse
from app.settings import settings
from app.storage import download_object
from app.task_store import StoredTask, task_store

_tasks: dict[str, ParseTaskResponse] = {}
_tasks_lock = Lock()
_document_locks: dict[str, Lock] = {}
_document_lock_refs: dict[str, int] = {}
_document_locks_guard = Lock()

# ---- 解析阶段进度（内存态） ----
# 进度是瞬时的 UI 状态：任务重启后本来就会从头重跑（恢复循环只认 ACCEPTED/PROCESSING），
# 所以它不进 MySQL —— 任务表只保存有持久语义的状态机字段（status/retry_count/失败原因）。
# 键为机器可读的阶段名，前端负责翻译文案；percent 取整数 0-100。
_task_progress: dict[str, tuple[int, str]] = {}
_task_progress_lock = Lock()

logger = logging.getLogger(__name__)


def _set_progress(task_id: str, percent: int, stage: str) -> None:
    with _task_progress_lock:
        _task_progress[task_id] = (percent, stage)


def _clear_progress(task_id: str) -> None:
    with _task_progress_lock:
        _task_progress.pop(task_id, None)


def _recorded_progress(task_id: str) -> tuple[int, str] | None:
    with _task_progress_lock:
        return _task_progress.get(task_id)


def _merge_progress(task: ParseTaskResponse) -> ParseTaskResponse:
    recorded = _recorded_progress(task.task_id)
    if recorded is None or task.status in {"COMPLETED", "FAILED"}:
        return task
    percent, stage = recorded
    return task.model_copy(update={"progress": percent, "stage": stage})


def submit_parse_task(request: ParseDocumentRequest) -> ParseTaskResponse:
    task = ParseTaskResponse(
        task_id=str(uuid4()),
        knowledge_base_id=request.knowledge_base_id,
        object_key=request.object_key,
        filename=request.filename,
        content_type=request.content_type,
        status="ACCEPTED",
        max_retries=settings.task_max_retries,
        replace_existing=request.replace_existing,
    )
    _remember(task)
    if settings.task_store_enabled:
        task_store.create(_to_stored(task))
    return task


def get_parse_task(task_id: str) -> ParseTaskResponse | None:
    if settings.task_store_enabled:
        stored = task_store.get(task_id)
        if stored is not None:
            task = _from_stored(stored)
            _remember(task)
            return _merge_progress(task)
    with _tasks_lock:
        task = _tasks.get(task_id)
    return _merge_progress(task) if task is not None else None


def retry_parse_task(task_id: str) -> ParseTaskResponse | None:
    # 重试会让解析从头跑一遍，上一轮残留的进度必须清掉，否则界面会短暂显示假进度。
    _clear_progress(task_id)
    if settings.task_store_enabled:
        stored = task_store.retry(task_id)
        if stored is None:
            return get_parse_task(task_id)
        task = _from_stored(stored)
        _remember(task)
        return task
    with _tasks_lock:
        task = _tasks.get(task_id)
        if task is None or task.status != "FAILED":
            return task
        retried = task.model_copy(update={
            "status": "ACCEPTED",
            "failure_reason": None,
            "retry_count": 0,
            "replace_existing": True,
        })
        _tasks[task_id] = retried
        return retried


# 心跳间隔的下限。真实间隔取「stale 阈值的 1/4」与此值中的较大者：
# 阈值默认 300s 时即每 75 秒续期一次，留出 4 倍余量去吸收网络抖动与时钟漂移。
_MIN_HEARTBEAT_SECONDS = 5.0


def _heartbeat_interval() -> float:
    return max(_MIN_HEARTBEAT_SECONDS, settings.task_processing_stale_seconds / 4)


class _TaskHeartbeat:
    """在处理期间持续刷新任务的 ``updated_at``，声明「我还活着」。

    没有它会发生什么：一个解析耗时超过 ``task_processing_stale_seconds`` 的大文档，
    会被恢复循环的 ``find_recoverable`` 判成僵尸任务，然后**从零再做一遍** ——
    重新下载、重新切片、重新调 embedding 接口，钱白花一倍，而且这是静默发生的，
    只在「文档够大」时才暴露出来。

    加上心跳之后，``updated_at`` 的语义从「最后一次状态变更」变成了
    「最后一次存活上报」：只有真正挂掉的进程才会停止上报、才会被回收，
    这正是僵尸检测想要的语义。

    为什么必须是后台线程，而不是「在解析循环里顺手更新一下」：真正的耗时段是
    ``index_chunks`` 内部的网络调用，中间没有任何可以让出来的位置，
    所以只能靠一个独立线程按固定节奏上报。
    """

    def __init__(self, task_id: str) -> None:
        self._task_id = task_id
        self._stop = Event()
        self._thread: Thread | None = None

    def __enter__(self) -> "_TaskHeartbeat":
        if not settings.task_store_enabled:
            return self
        self._thread = Thread(
            target=self._beat,
            name=f"task-heartbeat-{self._task_id[:8]}",
            daemon=True,
        )
        self._thread.start()
        return self

    def __exit__(self, *_exc_info: object) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _beat(self) -> None:
        interval = _heartbeat_interval()
        # Event.wait 既是「等待」也是「收到停止信号就立刻返回」，比 sleep 更干净：
        # 任务提前结束时不用陪跑完一整个间隔。
        while not self._stop.wait(interval):
            try:
                task_store.touch(self._task_id)
            except Exception:
                # 心跳失败不能反过来弄砸解析本身：真续不上期，就让僵尸检测照常兜底。
                logger.warning("failed to heartbeat document processing task %s", self._task_id)


def process_parse_task(task_id: str, request: ParseDocumentRequest | None = None) -> None:
    task = get_parse_task(task_id)
    if task is None:
        return
    recovered_processing = task.status == "PROCESSING"
    request = request or ParseDocumentRequest(
        object_key=task.object_key,
        filename=task.filename,
        content_type=task.content_type,
        knowledge_base_id=task.knowledge_base_id,
        replace_existing=task.replace_existing,
    )
    if not _claim_task(task_id):
        return

    # 抢到任务的那一刻起，「我」就是这个任务的所有者，必须持续上报存活；
    # 否则解析一旦超过 stale 阈值，恢复循环就会把我手上的任务再派一遍。
    with _TaskHeartbeat(task_id):
        document_lock = _document_lock(request.knowledge_base_id, request.object_key)
        try:
            with document_lock:
                while True:
                    task = get_parse_task(task_id) or task
                    _set_task(task_id, "PROCESSING", None, task.retry_count)
                    _set_progress(task_id, 5, "claimed")
                    try:
                        content = download_object(request.object_key)
                        _set_progress(task_id, 25, "downloaded")
                        # 抽取与切片一步到位：结构信息必须从抽取阶段就带出来，
                        # 否则切片只能在无结构的一长串字符上猜边界。详见 app/chunking.py
                        chunks = chunk_document(
                            content,
                            request.content_type,
                            request.filename,
                            settings.chunk_size,
                            settings.chunk_overlap,
                        )
                        if not chunks:
                            raise ValueError("document does not contain extractable text")
                        _set_progress(task_id, 45, "chunked")
                        index_chunks(
                            request.knowledge_base_id,
                            request.object_key,
                            chunks,
                            request.replace_existing or recovered_processing or task.retry_count > 0,
                            # embedding 是流水线里最长的一段，逐片上报：
                            # 进度在 50-90 之间随完成片数推进，给用户真实的等待预期。
                            on_progress=lambda done, total: _set_progress(
                                task_id, 50 + int(40 * done / max(total, 1)), "embedding"
                            ),
                        )
                        _clear_progress(task_id)
                        _set_task(task_id, "COMPLETED", None, task.retry_count)
                        return
                    except Exception as exception:
                        next_retry_count = task.retry_count + 1
                        failure_reason = _failure_reason(exception)
                        _clear_progress(task_id)
                        if next_retry_count <= task.max_retries:
                            _set_task(task_id, "RETRYING", failure_reason, next_retry_count)
                            time.sleep(settings.task_retry_backoff_seconds * (2 ** (next_retry_count - 1)))
                            if not _claim_task(task_id):
                                return
                            continue
                        _set_task(task_id, "FAILED", failure_reason, task.retry_count)
                        logger.exception("document processing task failed: %s", task_id)
                        return
        finally:
            _release_document_lock(request.knowledge_base_id, request.object_key)


def recover_parse_tasks() -> list[str]:
    if not settings.task_store_enabled:
        return []
    task_ids = []
    for task in task_store.find_recoverable():
        process_parse_task(task.task_id)
        task_ids.append(task.task_id)
    return task_ids


def _claim_task(task_id: str) -> bool:
    if settings.task_store_enabled:
        claimed = task_store.claim(task_id)
        if claimed:
            stored = task_store.get(task_id)
            if stored is not None:
                _remember(_from_stored(stored))
        return claimed
    with _tasks_lock:
        task = _tasks.get(task_id)
        if task is None or task.status not in {"ACCEPTED", "RETRYING"}:
            return False
        _tasks[task_id] = task.model_copy(update={"status": "PROCESSING"})
        return True


def _set_task(task_id: str, status: str, failure_reason: str | None, retry_count: int) -> None:
    if settings.task_store_enabled:
        stored = task_store.update(task_id, status, failure_reason, retry_count)
        if stored is not None:
            _remember(_from_stored(stored))
        return
    with _tasks_lock:
        task = _tasks.get(task_id)
        if task is not None:
            _tasks[task_id] = task.model_copy(
                update={"status": status, "failure_reason": failure_reason, "retry_count": retry_count}
            )


def _remember(task: ParseTaskResponse) -> None:
    with _tasks_lock:
        _tasks[task.task_id] = task


def _document_lock(knowledge_base_id: int, object_key: str) -> Lock:
    lock_key = f"{knowledge_base_id}:{object_key}"
    with _document_locks_guard:
        lock = _document_locks.setdefault(lock_key, Lock())
        # 引用计数：同一个文档可能被多个任务并发处理，只有当最后一个使用者
        # 释放后才能真正回收，否则会出现「等待旧锁 / 新建新锁」的竞态。
        _document_lock_refs[lock_key] = _document_lock_refs.get(lock_key, 0) + 1
        return lock


def _release_document_lock(knowledge_base_id: int, object_key: str) -> None:
    lock_key = f"{knowledge_base_id}:{object_key}"
    with _document_locks_guard:
        remaining = _document_lock_refs.get(lock_key, 0) - 1
        if remaining <= 0:
            _document_lock_refs.pop(lock_key, None)
            _document_locks.pop(lock_key, None)
        else:
            _document_lock_refs[lock_key] = remaining


def _to_stored(task: ParseTaskResponse) -> StoredTask:
    return StoredTask(
        task_id=task.task_id,
        knowledge_base_id=task.knowledge_base_id,
        object_key=task.object_key,
        filename=task.filename,
        content_type=task.content_type,
        status=task.status,
        failure_reason=task.failure_reason,
        retry_count=task.retry_count,
        max_retries=task.max_retries,
        replace_existing=task.replace_existing,
    )


def _from_stored(task: StoredTask) -> ParseTaskResponse:
    return ParseTaskResponse(
        task_id=task.task_id,
        knowledge_base_id=task.knowledge_base_id,
        object_key=task.object_key,
        filename=task.filename,
        content_type=task.content_type,
        status=task.status,
        failure_reason=task.failure_reason,
        retry_count=task.retry_count,
        max_retries=task.max_retries,
        replace_existing=task.replace_existing,
    )


def _failure_reason(exception: Exception) -> str:
    message = str(exception).strip() or exception.__class__.__name__
    return f"{exception.__class__.__name__}: {message}"[:2000]
