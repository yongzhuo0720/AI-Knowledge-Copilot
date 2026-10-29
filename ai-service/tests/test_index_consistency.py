"""删除与在途索引的竞态（R15）。

复现路径：解析任务正在 embedding（每片一次 HTTP），用户同时删除这篇文档。
删除先完成、索引的 insert 随后到达 —— 元数据已经没了，向量却还在，
检索仍能命中一份「删不掉」的内容。修复要求删除与写入互斥，并且删除在
写入临界区内可见（墓碑），旧任务不能靠重试把内容写回来。
"""

import sys
import threading
from types import SimpleNamespace

import pytest

from app import indexing


def _mock_milvus(monkeypatch, collection) -> None:
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(
        Collection=lambda *args: collection,
        CollectionSchema=lambda fields: fields,
        DataType=SimpleNamespace(VARCHAR="varchar", INT64="int64", FLOAT_VECTOR="vector"),
        FieldSchema=lambda **kwargs: kwargs,
        connections=SimpleNamespace(connect=lambda **kwargs: None),
        utility=SimpleNamespace(has_collection=lambda name: True),
    ))


@pytest.fixture(autouse=True)
def _fresh_deletion_marks():
    """墓碑是进程级状态，测试之间必须清干净，否则后面的用例会被前一个的删除挡住。"""
    indexing._deleted_documents.clear()
    indexing._deleted_knowledge_bases.clear()
    yield
    indexing._deleted_documents.clear()
    indexing._deleted_knowledge_bases.clear()


def _collection(inserted: list, deleted: list) -> SimpleNamespace:
    return SimpleNamespace(
        insert=lambda columns: inserted.append(columns),
        delete=lambda expr: deleted.append(expr),
        flush=lambda: None,
    )


def test_deletion_during_embedding_prevents_the_inflight_index_from_writing(monkeypatch) -> None:
    inserted: list = []
    deleted: list = []
    _mock_milvus(monkeypatch, _collection(inserted, deleted))
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", lambda knowledge_base_id: None)
    embedding_started = threading.Event()
    release_embedding = threading.Event()

    def paused_embed(chunk: str) -> list[float]:
        embedding_started.set()
        assert release_embedding.wait(timeout=5), "embedding 没有被放行"
        return [1.0]

    monkeypatch.setattr(indexing, "embed", paused_embed)
    failures: list[BaseException] = []

    def run_index() -> None:
        try:
            indexing.index_chunks(7, "kb/7/deleted.txt", ["deleted document content"])
        except BaseException as exception:  # noqa: BLE001 - 线程里的异常要带回主线程断言
            failures.append(exception)

    worker = threading.Thread(target=run_index)
    worker.start()
    assert embedding_started.wait(timeout=5), "embedding 没有按预期暂停"
    indexing.delete_chunks(7, "kb/7/deleted.txt")
    release_embedding.set()
    worker.join(timeout=5)
    assert not worker.is_alive()

    assert inserted == [], "已删除文档的向量被在途任务写回来了"
    assert len(failures) == 1 and isinstance(failures[0], indexing.IndexCancelledError)


def test_a_document_deleted_before_indexing_is_not_reindexed_by_stale_tasks(monkeypatch) -> None:
    """删除先完成、旧任务后到：不能只靠「删除发生在 embedding 期间」这一条路径拦截。"""
    inserted: list = []
    embedded: list = []
    _mock_milvus(monkeypatch, _collection(inserted, []))
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", lambda knowledge_base_id: None)
    monkeypatch.setattr(indexing, "embed", lambda chunk: embedded.append(chunk) or [1.0])

    indexing.delete_chunks(7, "kb/7/deleted.txt")

    with pytest.raises(indexing.IndexCancelledError):
        indexing.index_chunks(7, "kb/7/deleted.txt", ["deleted document content"])

    assert inserted == []
    assert embedded == [], "已删除的文档不该再花 embedding 的钱"


def test_deleting_a_whole_knowledge_base_also_blocks_inflight_indexing(monkeypatch) -> None:
    inserted: list = []
    _mock_milvus(monkeypatch, _collection(inserted, []))
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", lambda knowledge_base_id: None)
    monkeypatch.setattr(indexing, "embed", lambda chunk: [1.0])

    indexing.delete_knowledge_base_chunks(42)

    with pytest.raises(indexing.IndexCancelledError):
        indexing.index_chunks(42, "kb/42/guide.txt", ["content"])
    assert inserted == []


def test_failed_vector_deletion_does_not_block_a_later_reindex(monkeypatch) -> None:
    """删除失败时不能立墓碑：后端会保留元数据允许重试，重新解析必须还能写入。"""
    inserted: list = []

    def failing_delete(expr: str) -> None:
        raise RuntimeError("milvus down")

    _mock_milvus(monkeypatch, SimpleNamespace(
        insert=lambda columns: inserted.append(columns),
        delete=failing_delete,
        flush=lambda: None,
    ))
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", lambda knowledge_base_id: None)
    monkeypatch.setattr(indexing, "embed", lambda chunk: [1.0])

    with pytest.raises(RuntimeError):
        indexing.delete_chunks(7, "kb/7/guide.txt")

    indexing.index_chunks(7, "kb/7/guide.txt", ["content"])
    assert inserted, "删除失败后重新解析必须还能写入"
