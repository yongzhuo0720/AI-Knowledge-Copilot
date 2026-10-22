import sys
from types import SimpleNamespace

import pytest

from app import hybrid_search, indexing


def _mock_milvus(monkeypatch, collection) -> None:
    monkeypatch.setitem(sys.modules, "pymilvus", SimpleNamespace(
        Collection=lambda *args: collection,
        CollectionSchema=lambda fields: fields,
        DataType=SimpleNamespace(VARCHAR="varchar", INT64="int64", FLOAT_VECTOR="vector"),
        FieldSchema=lambda **kwargs: kwargs,
        connections=SimpleNamespace(connect=lambda **kwargs: None),
        utility=SimpleNamespace(has_collection=lambda name: True),
    ))


@pytest.mark.parametrize("text", ["中" * 1470, "🙂" * 1200, "标题\n\n" + "中文🙂" * 1000])
def test_indexing_splits_oversized_utf8_content_without_losing_text(monkeypatch, text) -> None:
    inserted = []

    def insert(columns):
        assert all(len(chunk.encode("utf-8")) <= 4096 for chunk in columns[3])
        inserted.append(columns)

    _mock_milvus(monkeypatch, SimpleNamespace(insert=insert, flush=lambda: None))
    embedded = []
    monkeypatch.setattr(indexing, "embed", lambda chunk: embedded.append(chunk) or [1.0])
    monkeypatch.setattr(indexing, "_invalidate_keyword_corpus", lambda knowledge_base_id: None)

    indexing.index_chunks(7, "kb/7/guide.txt", [text])

    columns = inserted[0]
    assert "".join(columns[3]) == text
    assert embedded == columns[3]
    assert all(len(column) == len(columns[3]) for column in columns)


@pytest.mark.parametrize("operation", [indexing.document_inventory, hybrid_search._fetch_corpus])
def test_large_corpus_reads_beyond_milvus_offset_window(monkeypatch, operation) -> None:
    rows = [{"object_key": "kb/7/guide.txt", "content": f"内容 {number}"} for number in range(17000)]
    position = 0
    closed = []

    def query(*, offset, limit, **kwargs):
        if offset + limit > 16384:
            raise ValueError("query results exceed the limit size")
        return rows[offset:offset + limit]

    def next_batch():
        nonlocal position
        batch = rows[position:position + 1000]
        position += len(batch)
        return batch

    def query_iterator(**kwargs):
        assert kwargs["expr"] == "knowledge_base_id == 7"
        return SimpleNamespace(next=next_batch, close=lambda: closed.append(True))

    _mock_milvus(monkeypatch, SimpleNamespace(
        load=lambda: None, query=query, query_iterator=query_iterator,
    ))

    result = operation(7)

    assert sum(result.values()) == 17000 if isinstance(result, dict) else len(result) == 17000
    assert closed == [True]


# --------------------------------------------------------------------------- #
# 两个 Milvus 硬限制的防御
# --------------------------------------------------------------------------- #

def test_split_oversized_content_never_breaks_a_character() -> None:
    """emoji 是 4 字节，按字节硬切会把它从中间劈开，产生非法 UTF-8 序列。"""
    text = "🙂" * 1200  # 4800 字节，必然超限

    parts = indexing.split_oversized_content(text)

    assert "".join(parts) == text, "切完必须能拼回原文，一个字符都不能丢"
    assert all(len(part.encode("utf-8")) <= indexing.MAX_CONTENT_BYTES for part in parts)
    # 每个片段都必须能独立编码解码 —— 硬切字节在这一步就会抛 UnicodeDecodeError
    assert all(part.encode("utf-8").decode("utf-8") == part for part in parts)


def test_split_oversized_content_leaves_small_chunks_untouched() -> None:
    assert indexing.split_oversized_content("短内容") == ["短内容"]


def test_split_oversized_content_packs_parts_up_to_the_limit() -> None:
    """2730 个汉字 = 8190 字节，应当刚好切成 2 段（每段 1365 字 = 4095 字节）。

    切得太碎会让同一个语义单元被拆到很多片里，检索时谁也答不全；
    这里断言的是「尽量塞满」，而不是「能不超就行」。
    """
    parts = indexing.split_oversized_content("中" * 2730)

    assert len(parts) == 2
    assert [len(part) for part in parts] == [1365, 1365]


def test_iter_all_rows_closes_the_iterator_even_when_next_fails(monkeypatch) -> None:
    """游标持有服务端资源，异常路径上也必须释放，否则会把连接池耗干。"""
    closed = []

    def next_batch():
        raise RuntimeError("milvus 掉了")

    def query(*, offset, limit, **kwargs):
        return [{"object_key": "k"}] * limit  # 撑满，逼实现走到游标分支

    collection = SimpleNamespace(
        query=query,
        query_iterator=lambda **kwargs: SimpleNamespace(next=next_batch, close=lambda: closed.append(True)),
    )

    with pytest.raises(RuntimeError):
        indexing.iter_all_rows(collection, "knowledge_base_id == 7", ["object_key"], batch_size=100)

    assert closed == [True]
