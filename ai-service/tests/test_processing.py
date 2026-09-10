from fastapi.testclient import TestClient

from app.main import app


def test_create_parse_task() -> None:
    response = TestClient(app).post(
        "/api/v1/document-processing/tasks",
        json={
            "object_key": "kb/20/guide.pdf",
            "filename": "guide.pdf",
            "content_type": "application/pdf",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == "0"
    assert body["data"]["object_key"] == "kb/20/guide.pdf"
    assert body["data"]["status"] == "ACCEPTED"
    assert body["data"]["task_id"]


def test_create_parse_task_accepts_camel_case_request() -> None:
    response = TestClient(app).post(
        "/api/v1/document-processing/tasks",
        json={
            "objectKey": "kb/20/guide.pdf",
            "filename": "guide.pdf",
            "contentType": "application/pdf",
        },
    )

    assert response.status_code == 200
    assert response.json()["data"]["object_key"] == "kb/20/guide.pdf"
