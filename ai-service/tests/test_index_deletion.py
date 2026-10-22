import sys
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import indexing, main


def _mock_milvus(monkeypatch, collection, has_collection: bool = True) -> None:
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(
        Collection=lambda *args: collection,
        connections=SimpleNamespace(connect=lambda **kwargs: None),
        utility=SimpleNamespace(has_collection=lambda name: has_collection),
    ))


def test_delete_knowledge_base_chunks_filters_by_knowledge_base(monkeypatch) -> None:
    deleted = []
    invalidated = []
    collection = SimpleNamespace(delete=lambda expr: deleted.append(expr), flush=lambda: None)
    _mock_milvus(monkeypatch, collection)
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", invalidated.append)

    indexing.delete_knowledge_base_chunks(42)

    assert deleted == ["knowledge_base_id == 42"]
    assert invalidated == [42]


def test_delete_knowledge_base_chunks_is_a_noop_without_the_collection(monkeypatch) -> None:
    deleted = []
    collection = SimpleNamespace(delete=lambda expr: deleted.append(expr), flush=lambda: None)
    _mock_milvus(monkeypatch, collection, has_collection=False)

    indexing.delete_knowledge_base_chunks(42)

    assert deleted == []


def test_delete_knowledge_base_index_endpoint(monkeypatch) -> None:
    calls = []
    monkeypatch.setattr(main, "delete_knowledge_base_chunks", calls.append)

    response = TestClient(main.app).delete("/api/v1/knowledge-bases/42/index")

    assert response.status_code == 200
    assert response.json()["data"] == {"knowledge_base_id": 42, "deleted": True}
    assert calls == [42]


def test_delete_document_index_endpoint_forwards_the_object_key(monkeypatch) -> None:
    calls = []
    monkeypatch.setattr(main, "delete_chunks", lambda knowledge_base_id, object_key: calls.append(
        (knowledge_base_id, object_key)))

    response = TestClient(main.app).delete(
        "/api/v1/knowledge-bases/42/documents/index",
        params={"object_key": "kb/42/uuid/产品 指南.pdf"},
    )

    assert response.status_code == 200
    assert calls == [(42, "kb/42/uuid/产品 指南.pdf")]
