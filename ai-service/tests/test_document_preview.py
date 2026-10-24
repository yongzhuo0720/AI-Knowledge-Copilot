"""文档预览端点：分节返回原文结构化内容。

与 Agent 的 document_outline 同源（重新下载原文走 extract_blocks），
这里验证的是 HTTP 层的契约：越权防线、分节分组顺序、体量截断。
"""

import pytest
from fastapi.testclient import TestClient

from app.document_blocks import DocumentBlock
from app.main import app
from app.settings import settings


@pytest.fixture()
def client() -> TestClient:
    return TestClient(app)


def _preview(client: TestClient, object_key: str) -> object:
    return client.get(
        "/api/v1/knowledge-bases/20/documents/preview",
        params={"object_key": object_key},
        headers={"X-Internal-Token": "secret-token"},
    )


def test_rejects_object_keys_outside_the_knowledge_base(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    # 其他知识库的前缀 / 目录穿越 / 缺少文件部分，一律 403：
    # object_key 是共享对象存储里的路径，不核前缀就能读任意对象。
    assert _preview(client, "kb/21/secret.md").status_code == 403
    assert _preview(client, "kb/20/../21/secret.md").status_code == 403
    assert _preview(client, "kb/20/").status_code == 403


def test_groups_blocks_by_heading_path_in_document_order(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")
    monkeypatch.setattr("app.main.download_object", lambda key: b"raw")
    blocks = [
        DocumentBlock(text="开头介绍。"),
        DocumentBlock(text="每天巡检三项。", heading_path=("日常巡检",)),
        DocumentBlock(text="电量低于 30% 回桩。", heading_path=("日常巡检",)),
        DocumentBlock(text="15 分钟内提工单。", heading_path=("故障上报",)),
    ]
    monkeypatch.setattr("app.main.extract_blocks", lambda content, content_type, filename: blocks)

    response = _preview(client, "kb/20/x/guide.md")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["name"] == "guide.md"
    assert data["truncated"] is False
    # 无标题的块归入空标题链；相邻同节合并，保持文档顺序
    assert [section["title_path"] for section in data["sections"]] == [[], ["日常巡检"], ["故障上报"]]
    assert data["sections"][1]["blocks"] == ["每天巡检三项。", "电量低于 30% 回桩。"]


def test_marks_truncation_when_section_limit_is_exceeded(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")
    monkeypatch.setattr("app.main.download_object", lambda key: b"raw")
    many = [DocumentBlock(text=f"第 {index} 段正文。", heading_path=(f"第{index}节",)) for index in range(80)]
    monkeypatch.setattr("app.main.extract_blocks", lambda content, content_type, filename: many)

    data = _preview(client, "kb/20/x/big.md").json()["data"]

    assert data["section_count"] == 60
    assert data["truncated"] is True


def test_reports_object_read_failures_as_bad_gateway(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(settings, "internal_token", "secret-token")

    def boom(key: str) -> bytes:
        raise RuntimeError("no such object")

    monkeypatch.setattr("app.main.download_object", boom)

    assert _preview(client, "kb/20/x/missing.md").status_code == 502
