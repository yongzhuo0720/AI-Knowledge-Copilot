import logging
import time
from io import BytesIO
from threading import Lock
from uuid import uuid4

from app.indexing import index_chunks, split_text
from app.schemas import ParseDocumentRequest, ParseTaskResponse
from app.settings import settings
from app.task_store import StoredTask, task_store

_tasks: dict[str, ParseTaskResponse] = {}
_tasks_lock = Lock()
_document_locks: dict[str, Lock] = {}
_document_locks_guard = Lock()
logger = logging.getLogger(__name__)


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
            return task
    with _tasks_lock:
        return _tasks.get(task_id)


def retry_parse_task(task_id: str) -> ParseTaskResponse | None:
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

    document_lock = _document_lock(request.knowledge_base_id, request.object_key)
    with document_lock:
        while True:
            task = get_parse_task(task_id) or task
            _set_task(task_id, "PROCESSING", None, task.retry_count)
            try:
                content = _download_object(request.object_key)
                text = _extract_text(content, request.content_type, request.filename)
                chunks = split_text(text)
                if not chunks:
                    raise ValueError("document does not contain extractable text")
                index_chunks(
                    request.knowledge_base_id,
                    request.object_key,
                    chunks,
                    request.replace_existing or recovered_processing or task.retry_count > 0,
                )
                _set_task(task_id, "COMPLETED", None, task.retry_count)
                return
            except Exception as exception:
                next_retry_count = task.retry_count + 1
                failure_reason = _failure_reason(exception)
                if next_retry_count <= task.max_retries:
                    _set_task(task_id, "RETRYING", failure_reason, next_retry_count)
                    time.sleep(settings.task_retry_backoff_seconds * (2 ** (next_retry_count - 1)))
                    if not _claim_task(task_id):
                        return
                    continue
                _set_task(task_id, "FAILED", failure_reason, task.retry_count)
                logger.exception("document processing task failed: %s", task_id)
                return


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
        return _document_locks.setdefault(lock_key, Lock())


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


def _download_object(object_key: str) -> bytes:
    from minio import Minio

    client = Minio(
        settings.minio_endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        secure=False,
    )
    response = client.get_object(settings.minio_bucket, object_key)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def _extract_text(content: bytes, content_type: str, filename: str) -> str:
    if content_type.startswith("text/") or filename.lower().endswith((".txt", ".md", ".csv")):
        return content.decode("utf-8", errors="replace")
    if content_type == "application/pdf" or filename.lower().endswith(".pdf"):
        from pypdf import PdfReader

        return "\n".join(page.extract_text() or "" for page in PdfReader(BytesIO(content)).pages)
    if content_type.endswith("wordprocessingml.document") or filename.lower().endswith(".docx"):
        from docx import Document

        return "\n".join(paragraph.text for paragraph in Document(BytesIO(content)).paragraphs)
    raise ValueError(f"unsupported document type: {content_type}")
