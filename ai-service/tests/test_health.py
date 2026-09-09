from fastapi.testclient import TestClient

from app.main import app


def test_health_endpoint() -> None:
    response = TestClient(app).get("/api/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "code": "0",
        "message": "success",
        "data": {
            "status": "UP",
            "service": "ai-knowledge-copilot-ai-service",
        },
    }
