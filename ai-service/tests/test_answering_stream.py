"""流式问答的终态契约。

上游模型流只有两种正常结束方式：收到 [DONE] 后发 complete。其余情况
（EOF 截断、error 帧、网络异常）都必须变成事件流里的 error，**不能**发出
complete —— backend 与前端都靠「有没有 complete」区分成功与半截答案。
"""

from typing import Iterator

import pytest

from app import answering
from app.settings import settings


class _FakeResponse:
    def __init__(self, lines: list[bytes]) -> None:
        self._lines = lines

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False

    def __iter__(self) -> Iterator[bytes]:
        return iter(self._lines)


class _BrokenResponse(_FakeResponse):
    def __iter__(self) -> Iterator[bytes]:
        yield b'data: {"choices":[{"delta":{"content":"\xe5\x8d\x8a"}}]}\n'
        raise ConnectionResetError("connection reset by peer")


def _collect(monkeypatch: pytest.MonkeyPatch, response: object) -> list[dict]:
    monkeypatch.setattr(
        answering, "retrieve_chunks",
        lambda *args, **kwargs: [
            {"document_object_key": "kb/1/a.md", "content": "资料", "score": 0.9, "retriever": "vector"}
        ],
    )
    monkeypatch.setattr(settings, "chat_api_key", "test-key")
    monkeypatch.setattr(answering, "urlopen", lambda *args, **kwargs: response)
    return list(answering.stream_answer_question(1, "问题"))


def _events(items: list[dict]) -> list[str]:
    return [str(item["event"]) for item in items]


def test_done_terminates_with_complete(monkeypatch: pytest.MonkeyPatch) -> None:
    items = _collect(monkeypatch, _FakeResponse([
        b'data: {"choices":[{"delta":{"content":"\xe5\xae\x8c\xe6\x95\xb4"}}]}\n',
        b'data: {"choices":[{"delta":{"content":"\xe5\x9b\x9e\xe7\xad\x94"}}]}\n',
        b"data: [DONE]\n",
    ]))

    assert _events(items) == ["sources", "delta", "delta", "complete"]
    assert items[-1]["data"]["answer"] == "完整回答"
    assert len(items[-1]["data"]["sources"]) == 1


def test_eof_without_done_is_error_not_complete(monkeypatch: pytest.MonkeyPatch) -> None:
    items = _collect(monkeypatch, _FakeResponse([
        b'data: {"choices":[{"delta":{"content":"\xe6\x88\xaa\xe6\x96\xad"}}]}\n',
    ]))

    assert _events(items) == ["sources", "delta", "error"]
    assert "未正常结束" in items[-1]["data"]["message"]


def test_error_frame_is_surfaced(monkeypatch: pytest.MonkeyPatch) -> None:
    items = _collect(monkeypatch, _FakeResponse([
        b'data: {"error":{"message":"quota exceeded","type":"insufficient_quota"}}\n',
    ]))

    assert _events(items) == ["sources", "error"]
    assert "quota exceeded" in items[-1]["data"]["message"]


def test_network_exception_becomes_error_event(monkeypatch: pytest.MonkeyPatch) -> None:
    items = _collect(monkeypatch, _BrokenResponse([]))

    assert _events(items) == ["sources", "delta", "error"]
    assert "connection reset" in items[-1]["data"]["message"]
