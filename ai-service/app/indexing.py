import hashlib
import math
import json
from threading import RLock
from urllib.request import Request, urlopen

DIMENSION = 1024
COLLECTION_NAME = "knowledge_chunks_v2"


def embed(text: str) -> list[float]:
    from app.settings import settings
    if settings.embedding_api_key:
        payload = {"model": settings.embedding_model, "input": text}
        if settings.embedding_dimensions > 0:
            payload["dimensions"] = settings.embedding_dimensions
        request = Request(
            f"{settings.embedding_base_url.rstrip('/')}/embeddings",
            data=json.dumps(payload).encode(),
            headers={"Authorization": f"Bearer {settings.embedding_api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=30) as response:
            return json.load(response)["data"][0]["embedding"]
    """Fallback only for local development when no embedding key is configured."""
    vector = [0.0] * DIMENSION
    for token in text.lower().split():
        index = int(hashlib.sha256(token.encode()).hexdigest(), 16) % DIMENSION
        vector[index] += 1.0
    norm = math.sqrt(sum(value * value for value in vector))
    return vector if norm == 0 else [value / norm for value in vector]


def _escape_object_key(object_key: str) -> str:
    """转义 Milvus 表达式里的字符串字面量。

    object_key 里可能含反斜杠或双引号（对象名由上传方决定），不转义会让
    ``object_key == "..."`` 这个表达式被提前闭合，轻则过滤失效，重则语法错误。
    """
    return object_key.replace('\\', '\\\\').replace('"', '\\"')


# --------------------------------------------------------------------------- #
# 删除屏障：in-flight 索引不能把已删除的文档写回来
# --------------------------------------------------------------------------- #
#
# 场景：解析任务正在 embedding（每片一次 HTTP，可能几十秒），用户同时删除了这篇文档。
# 删除先完成、索引随后 insert —— MySQL 元数据已经没了，向量却还在：检索命中得到，
# 又没有任何记录指向它，等于一份删不掉的残留内容。
#
# 做法：写入与删除共用一把进程内锁。删除在锁内完成「删向量 → 立墓碑」，
# 索引在锁内复查墓碑后才写入；看到墓碑就抛 IndexCancelledError 放弃本次写入。
# 墓碑是永久的：被删文档的 object_key 不会再被合法复用（上传每次都生成新的 UUID 目录），
# 不存在「删除后还要重新索引同一个 key」的正常路径。
#
# 适用范围：ai-service 以**单 worker** 部署（Dockerfile 未加 --workers），解析与删除
# 都在同一进程里，进程内锁足够；多副本部署要换成 Milvus 侧版本号或共享协调者。
# 墓碑只活在内存里，重启即清空 —— 重启后恢复循环只会捡起 MySQL 里 ACCEPTED/RETRYING
# 的任务，而那些文档的对象已随删除清掉，下载阶段就会失败，写不回向量。


class IndexCancelledError(RuntimeError):
    """写入前发现目标文档/知识库已被删除，本次索引放弃。

    **不可重试**：重试只会把已删除的内容写回去。解析任务必须把它当终态处理，
    而不是走普通的失败重试。
    """


# 可重入：写入临界区里会再次调用 _assert_not_deleted 复查同一组墓碑。
_index_write_lock = RLock()
_deleted_documents: set[tuple[int, str]] = set()
_deleted_knowledge_bases: set[int] = set()


def _assert_not_deleted(knowledge_base_id: int, object_key: str) -> None:
    with _index_write_lock:
        if knowledge_base_id in _deleted_knowledge_bases:
            raise IndexCancelledError(f"knowledge base {knowledge_base_id} was deleted; indexing cancelled")
        if (knowledge_base_id, object_key) in _deleted_documents:
            raise IndexCancelledError(
                f"document {object_key} in knowledge base {knowledge_base_id} was deleted; indexing cancelled"
            )


# --------------------------------------------------------------------------- #
# 与 Milvus 的两个硬限制打交道
# --------------------------------------------------------------------------- #

# Milvus 的 content 字段声明为 VARCHAR(4096)，上限按**字节**算，不是字符。
# 切片层按 chunk_size（默认 800 字符）打包，正常情况下远不到上限；但 index_chunks
# 是个公开函数，调用方可以直接把未切片的文本传进来（重建索引脚本、解析降级路径）。
# 一旦超限，Milvus 会**整批 insert 失败** —— 不是丢一段，是整个文档入库挂掉。
MAX_CONTENT_BYTES = 4096


def split_oversized_content(chunk: str, max_bytes: int = MAX_CONTENT_BYTES) -> list[str]:
    """把超出字节上限的片段切开，保证「拼回去等于原文」且「不切断字符」。

    为什么要按**字符**遍历而不是 ``chunk[:max_bytes]`` 按字节硬切：
    一个汉字 3 字节、一个 emoji 4 字节，硬切会把它们从中间劈开，产生非法 UTF-8 序列 ——
    Milvus 会拒绝，嵌入接口也会报错。而那个错误信息不会告诉你是「切片切坏了」。
    """
    if len(chunk.encode("utf-8")) <= max_bytes:
        return [chunk]

    parts: list[str] = []
    current: list[str] = []
    size = 0
    for char in chunk:
        width = len(char.encode("utf-8"))
        # `current` 非空才切：否则单个字符本身就超限时，会切出一个空片段并死循环。
        if size + width > max_bytes and current:
            parts.append("".join(current))
            current = []
            size = 0
        current.append(char)
        size += width
    if current:
        parts.append("".join(current))
    return parts


# Milvus 的 query 有一个硬上限：offset + limit 不能超过 16384。
# 只按 offset 递增分页的话，语料一旦超过这个量就会直接抛
# "query results exceed the limit size" —— 现象是**知识库小的时候一切正常，
# 大一点就整体检索失败**，属于最难在生产上复现的一类问题。
MILVUS_QUERY_OFFSET_CEILING = 16384


def iter_all_rows(
    collection,
    expression: str,
    output_fields: list[str],
    batch_size: int = 1000,
) -> list[dict]:
    """分页取出全部匹配行，**跨过 Milvus query 的 offset 上限**。

    先用 ``query`` + offset 分页（小知识库的常见路径，也是最稳的那条）；
    只有当结果超过 16384 行、offset 已经推不动时才改用 ``query_iterator``（服务端游标）。

    **改用游标时会把前面取到的全部丢弃、从头再取一遍。** 这不是疏漏，是有意的：
    游标路径只应该在「真的很大」的知识库上被走到，没必要让所有知识库都去承担
    它可能带来的兼容性风险。多跑的那 16 次 query 相对「整库检索失败」是可以接受的代价。
    """
    rows: list[dict] = []
    offset = 0
    while offset + batch_size <= MILVUS_QUERY_OFFSET_CEILING:
        batch = collection.query(
            expr=expression,
            output_fields=output_fields,
            limit=batch_size,
            offset=offset,
        )
        if not batch:
            return rows
        rows.extend(batch)
        if len(batch) < batch_size:
            return rows
        offset += batch_size

    rows = []
    iterator = collection.query_iterator(
        expr=expression,
        output_fields=output_fields,
        batch_size=batch_size,
    )
    try:
        while True:
            batch = iterator.next()
            if not batch:
                return rows
            rows.extend(batch)
    finally:
        # 游标持有服务端资源，异常路径上也必须释放 —— 否则一次失败就会留下一个
        # 悬空的迭代器，累积下去会把 Milvus 的连接池耗干。
        iterator.close()


def delete_chunks(knowledge_base_id: int, object_key: str) -> None:
    """删除单篇文档的向量，并立起墓碑挡住可能还在途的索引任务。

    墓碑只在删除**成功**后才立：Milvus 不可用时这里会抛异常，后端随即保留元数据
    让用户重试（fail-closed，见后端 ``KnowledgeService.deleteDocument``）；
    此时不能把文档标成「已删除」，否则重试成功后重新解析会被误取消。
    """
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    with _index_write_lock:
        # 集合不存在 = 本来就没有可删的向量；仍然要立墓碑：
        # 并发的 index_chunks 可能正准备创建集合并写入。
        if utility.has_collection(COLLECTION_NAME):
            collection = Collection(COLLECTION_NAME)
            collection.delete(
                f'knowledge_base_id == {knowledge_base_id} and object_key == "{_escape_object_key(object_key)}"'
            )
            collection.flush()
        _deleted_documents.add((knowledge_base_id, object_key))
    # 语料变了，关键词索引必须失效，否则 BM25 会继续在旧语料上打分
    _invalidate_keyword_corpus(knowledge_base_id)


def delete_knowledge_base_chunks(knowledge_base_id: int) -> None:
    """删除整个知识库的向量。

    管理员删库时调用：只删 MySQL 行会让向量继续留在 Milvus 里，既能被直接
    查询到，也会让同名知识库重建时检索到已删除的内容。删除是幂等的。
    删除成功后立知识库级墓碑，挡住在途的索引任务把内容写回来。
    """
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    with _index_write_lock:
        if utility.has_collection(COLLECTION_NAME):
            collection = Collection(COLLECTION_NAME)
            collection.delete(f"knowledge_base_id == {knowledge_base_id}")
            collection.flush()
        _deleted_knowledge_bases.add(knowledge_base_id)
    _invalidate_keyword_corpus(knowledge_base_id)


def index_chunks(
    knowledge_base_id: int,
    object_key: str,
    chunks: list[str],
    replace_existing: bool = False,
    on_progress=None,
) -> None:
    """把切片写入 Milvus。

    :param on_progress: 可选回调 ``on_progress(done, total)``，每完成一片
        embedding 调用一次。解析任务用它把「正在生成向量」的进度报给前端；
        其他调用方（重建索引脚本、检索链路）不传即可，零影响。
    :raises IndexCancelledError: 目标文档/知识库已在本次索引期间被删除。
        这是**终态**，调用方不能重试。
    """
    from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility
    from app.settings import settings

    # 先查墓碑再连 Milvus：文档已经删了，就不该再为它花 embedding 的钱。
    _assert_not_deleted(knowledge_base_id, object_key)
    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        schema = CollectionSchema([
            FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
            FieldSchema(name="knowledge_base_id", dtype=DataType.INT64),
            FieldSchema(name="object_key", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=4096),
            FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=DIMENSION),
        ])
        collection = Collection(COLLECTION_NAME, schema)
        collection.create_index("embedding", {"index_type": "FLAT", "metric_type": "IP", "params": {}})
    else:
        collection = Collection(COLLECTION_NAME)
    # 必须在 embed **之前**按字节切好：嵌入的必须正好是要入库的那份文本，
    # 否则向量和内容对不上（检索按截断后的内容命中，返回的却是另一段）。
    chunks = [part for chunk in chunks for part in split_oversized_content(chunk)]
    embeddings = []
    total = len(chunks)
    for done, chunk in enumerate(chunks, start=1):
        embeddings.append(embed(chunk))
        if on_progress is not None:
            on_progress(done, total)
    identifiers = [hashlib.sha256(f"{object_key}:{index}".encode()).hexdigest() for index in range(len(chunks))]
    # embedding 是整条链路里最慢的一段（每片一次 HTTP），删除随时可能在这期间插进来。
    # 删除方持有同一把锁、且在删完向量之后才立墓碑：所以这里读不到墓碑，就说明我的
    # 写入要么排在删除之前（随后被它一并删掉），要么删除还没开始。
    with _index_write_lock:
        _assert_not_deleted(knowledge_base_id, object_key)
        if replace_existing:
            collection.delete(
                f'knowledge_base_id == {knowledge_base_id} and object_key == "{_escape_object_key(object_key)}"'
            )
            collection.flush()
        collection.insert(
            [identifiers, [knowledge_base_id] * len(chunks), [object_key] * len(chunks), chunks, embeddings]
        )
        collection.flush()
    # 新文档入库后必须让关键词索引失效，否则新内容在 BM25 里搜不到（最长要到缓存过期才可见）
    _invalidate_keyword_corpus(knowledge_base_id)


def _invalidate_keyword_corpus(knowledge_base_id: int) -> None:
    try:
        from app.hybrid_search import invalidate_corpus

        invalidate_corpus(knowledge_base_id)
    except Exception:  # 缓存失效失败不该让入库/删除整体失败
        pass


def vector_search(
    knowledge_base_id: int,
    query: str,
    limit: int,
    document: str | None = None,
) -> list[dict[str, object]]:
    """纯向量检索（双编码器 + 内积）。

    这是混合检索的一条腿，保留为独立函数是为了能单独对比两路召回的效果差异。

    按文档过滤**下推到 Milvus 表达式**而不是取回后再筛：向量检索自带 top-k 截断，
    先把候选截到 limit 再筛会让「某篇文档里的第 1 名」在全局排到 limit 之外时被丢掉，
    过滤结果直接变空。过滤条件交给 Milvus，它会在候选集上先筛再算 top-k。
    """
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return []
    collection = Collection(COLLECTION_NAME)
    collection.load()
    expression = f"knowledge_base_id == {knowledge_base_id}"
    if document:
        expression += f' and object_key == "{_escape_object_key(document)}"'
    results = collection.search([embed(query)], "embedding", {"metric_type": "IP", "params": {}}, limit=limit,
                                expr=expression, output_fields=["object_key", "content"])
    return [
        {
            "document_object_key": hit.entity.get("object_key"),
            "content": hit.entity.get("content"),
            "score": hit.score,
            "retriever": "vector",
        }
        for hit in results[0]
    ]


def document_inventory(knowledge_base_id: int) -> dict[str, int]:
    """列出该知识库已建索引的文档及其片段数，返回 ``{object_key: chunk 数}``。

    只取 ``object_key`` 一个字段（不拉 content），所以即使知识库很大也很轻。
    分页拉取是因为 Milvus 单次 query 有结果窗口上限，一次取不完会被静默截断 ——
    截断的后果是「文档清单少了几篇」，这种错误不会报错，只会悄悄给出错的答案。

    为什么不去读后端 ``knowledge_document`` 表拿更权威的文件名和状态：
    ai-service 反查 backend 的表会造成双向依赖（backend 已经在调 ai-service），
    而且 Agent 真正需要知道的是「哪些内容可被检索」，这恰好就是向量库里的东西。
    代价是这里看不到「已上传但解析失败」的文档 —— 那属于后台文档管理页的职责。
    """
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return {}
    collection = Collection(COLLECTION_NAME)
    collection.load()

    counts: dict[str, int] = {}
    for row in iter_all_rows(collection, f"knowledge_base_id == {knowledge_base_id}", ["object_key"]):
        key = str(row.get("object_key") or "")
        counts[key] = counts.get(key, 0) + 1
    return counts


def document_preview_chunks(knowledge_base_id: int, document: str, limit: int) -> list[str]:
    """取某篇文档的若干片段正文，供 Agent 判断「这篇文档大概讲什么」。

    拿到的**不是「开头几段」，而是若干片段样本**：Milvus 的 ``query`` 不保证返回顺序，
    而这里的主键是内容哈希（本身无序），所以顺序无从约束。这恰好够用 —— 每片正文开头
    都带着切片时写入的小节标题前缀，抽几片就能看出这篇文档覆盖了哪些主题。

    每篇文档发一次 ``limit`` 很小的 query，而不是把整个知识库的 content 拉回来再按
    object_key 分组：后者在大知识库上会把全部正文读进内存，只为取几行预览。
    """
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    if limit <= 0:
        return []
    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return []
    collection = Collection(COLLECTION_NAME)
    collection.load()
    rows = collection.query(
        expr=f'knowledge_base_id == {knowledge_base_id} and object_key == "{_escape_object_key(document)}"',
        output_fields=["content"],
        limit=limit,
    )
    return [str(row.get("content") or "") for row in rows]


def retrieve_chunks(
    knowledge_base_id: int,
    query: str,
    limit: int,
    mode: str | None = None,
    document: str | None = None,
    rewrite: bool | None = None,
) -> list[dict[str, object]]:
    """统一检索入口：默认走混合检索（向量 + BM25 + RRF + 重排）。

    保留这个函数名是为了不打断既有调用方（问答、Agent）。想只要向量检索时传 ``mode="vector"``。

    :param document: 只在该 object_key 的文档内检索；None 表示整个知识库
    :param rewrite: 是否先用模型做查询改写（Multi-Query）。
        ``None`` 表示按配置 ``query_rewrite_enabled`` 走；
        Agent 的工具检索显式传 ``False`` —— 那条路径上查询是**模型自己写的**，
        而且它会跨轮换措辞重试，本身已经在做多查询了，工具内部再套一层
        等于每轮多打一次模型，收益却高度重叠。详见 ``app/query_rewrite``。
    """
    # 函数内导入：indexing 与 hybrid_search 互相依赖，模块级导入会形成循环。
    from app.hybrid_search import hybrid_retrieve, multi_query_retrieve
    from app.settings import settings

    use_rewrite = (
        bool(settings.query_rewrite_enabled) and bool(settings.chat_api_key)
        if rewrite is None
        else rewrite
    )

    try:
        if use_rewrite:
            return multi_query_retrieve(knowledge_base_id, query, limit, mode, document)
        return hybrid_retrieve(knowledge_base_id, query, limit, mode, document)
    except Exception:
        # 混合检索依赖 BM25 语料加载，任一环节出问题都不能让问答整体失败 —— 退回纯向量。
        import logging

        logging.getLogger(__name__).warning("hybrid retrieval failed; falling back to vector search", exc_info=True)
        return vector_search(knowledge_base_id, query, limit, document)


def count_chunks(knowledge_base_ids: list[int]) -> int:
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    if not knowledge_base_ids:
        return 0
    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return 0
    collection = Collection(COLLECTION_NAME)
    expression = ",".join(str(knowledge_base_id) for knowledge_base_id in knowledge_base_ids)
    rows = collection.query(
        expr=f"knowledge_base_id in [{expression}]",
        output_fields=["count(*)"],
    )
    return int(rows[0].get("count(*)", 0)) if rows else 0
