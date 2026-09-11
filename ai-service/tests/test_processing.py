from fastapi.testclient import TestClient

from app.main import app
from app.processing import _extract_text, get_parse_task, process_parse_task, retry_parse_task, submit_parse_task
from app.schemas import ParseDocumentRequest


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


def test_extracts_plain_text() -> None:
    assert _extract_text("团队知识库".encode(), "text/plain", "guide.txt") == "团队知识库"


def test_processing_task_completes_after_extracting_and_indexing(monkeypatch) -> None:
    request = ParseDocumentRequest(
        object_key="kb/20/guide.txt", filename="guide.txt", content_type="text/plain", knowledge_base_id=20
    )
    task = submit_parse_task(request)
    monkeypatch.setattr("app.processing._download_object", lambda _: "团队知识库内容".encode())
    monkeypatch.setattr("app.processing.index_chunks", lambda *args: None)

    process_parse_task(task.task_id, request)

    assert get_parse_task(task.task_id).status == "COMPLETED"


def test_processing_task_persists_failure_reason_and_retry_count_in_memory_fallback(monkeypatch) -> None:
    request = ParseDocumentRequest(
        object_key="kb/20/guide.bin", filename="guide.bin", content_type="application/octet-stream", knowledge_base_id=20
    )
    monkeypatch.setattr("app.processing.settings.task_max_retries", 0)
    task = submit_parse_task(request)
    monkeypatch.setattr("app.processing._download_object", lambda _: b"content")

    process_parse_task(task.task_id, request)

    failed = get_parse_task(task.task_id)
    assert failed.status == "FAILED"
    assert failed.retry_count == 0
    assert "ValueError" in failed.failure_reason

    retried = retry_parse_task(task.task_id)
    assert retried.status == "ACCEPTED"
    assert retried.retry_count == 0
