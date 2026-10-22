import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.settings import settings


@pytest.fixture()
def client() -> TestClient:
    return TestClient(app)


def test_health_is_public_even_when_token_is_configured(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    assert client.get("/api/v1/health").status_code == 200


def test_non_health_endpoint_rejects_missing_token(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    response = client.post("/api/v1/retrieval/search", json={})

    assert response.status_code == 401


def test_non_health_endpoint_rejects_wrong_token(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    response = client.post(
        "/api/v1/retrieval/search",
        json={},
        headers={"X-Internal-Token": "not-the-token"},
    )

    assert response.status_code == 401


def test_matching_token_reaches_the_endpoint(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    # 空 body 会被参数校验拦下（422），说明请求已经穿过鉴权层到了路由。
    response = client.post(
        "/api/v1/retrieval/search",
        json={},
        headers={"X-Internal-Token": "secret-token"},
    )

    assert response.status_code == 422


def test_validation_stays_open_when_no_token_is_configured(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "")

    response = client.post("/api/v1/retrieval/search", json={})

    assert response.status_code == 422
