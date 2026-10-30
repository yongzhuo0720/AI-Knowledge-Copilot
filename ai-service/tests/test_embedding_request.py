"""Embedding 请求体：dimensions 必须按 provider 能力可选。

硅基流动的 BAAI 系列（如 bge-m3）不接受 dimensions 参数，传入会直接 400；
而 DashScope 的 text-embedding-v4 需要靠它把向量收敛到 1024 维。
"""
import json

from app import indexing
from app.settings import settings


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def read(self):
        return json.dumps(self._payload).encode()


def _stub_urlopen(monkeypatch, captured):
    def fake_urlopen(request, timeout=0):
        captured["body"] = json.loads(request.data)
        return _FakeResponse({"data": [{"embedding": [0.0] * indexing.DIMENSION}]})

    monkeypatch.setattr(indexing, "urlopen", fake_urlopen)


def test_embed_sends_dimensions_when_configured(monkeypatch):
    captured = {}
    _stub_urlopen(monkeypatch, captured)
    monkeypatch.setattr(settings, "embedding_api_key", "test-key")
    monkeypatch.setattr(settings, "embedding_dimensions", 1024)

    vector = indexing.embed("hello")

    assert len(vector) == indexing.DIMENSION
    assert captured["body"]["dimensions"] == 1024


def test_embed_omits_dimensions_when_provider_rejects_it(monkeypatch):
    captured = {}
    _stub_urlopen(monkeypatch, captured)
    monkeypatch.setattr(settings, "embedding_api_key", "test-key")
    monkeypatch.setattr(settings, "embedding_dimensions", 0)

    indexing.embed("hello")

    assert "dimensions" not in captured["body"]
    assert captured["body"]["model"] == settings.embedding_model
