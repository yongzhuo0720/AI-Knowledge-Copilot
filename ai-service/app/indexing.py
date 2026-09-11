import hashlib
import math
import json
from urllib.request import Request, urlopen

DIMENSION = 1024
COLLECTION_NAME = "knowledge_chunks_v2"


def embed(text: str) -> list[float]:
    from app.settings import settings
    if settings.embedding_api_key:
        request = Request(
            f"{settings.embedding_base_url.rstrip('/')}/embeddings",
            data=json.dumps({"model": settings.embedding_model, "input": text, "dimensions": DIMENSION}).encode(),
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


def split_text(text: str, size: int = 800, overlap: int = 120) -> list[str]:
    text = " ".join(text.split())
    if not text:
        return []
    return [text[start:start + size] for start in range(0, len(text), size - overlap)]


def delete_chunks(knowledge_base_id: int, object_key: str) -> None:
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return
    collection = Collection(COLLECTION_NAME)
    escaped_object_key = object_key.replace('\\', '\\\\').replace('"', '\\"')
    collection.delete(f'knowledge_base_id == {knowledge_base_id} and object_key == "{escaped_object_key}"')
    collection.flush()


def index_chunks(knowledge_base_id: int, object_key: str, chunks: list[str], replace_existing: bool = False) -> None:
    from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility
    from app.settings import settings

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
    embeddings = [embed(chunk) for chunk in chunks]
    if replace_existing:
        escaped_object_key = object_key.replace('\\', '\\\\').replace('"', '\\"')
        collection.delete(f'knowledge_base_id == {knowledge_base_id} and object_key == "{escaped_object_key}"')
        collection.flush()
    identifiers = [hashlib.sha256(f"{object_key}:{index}".encode()).hexdigest() for index in range(len(chunks))]
    collection.insert([identifiers, [knowledge_base_id] * len(chunks), [object_key] * len(chunks), chunks, embeddings])
    collection.flush()


def retrieve_chunks(knowledge_base_id: int, query: str, limit: int) -> list[dict[str, object]]:
    from pymilvus import Collection, connections, utility
    from app.settings import settings

    connections.connect(alias="default", host=settings.milvus_host, port=str(settings.milvus_port))
    if not utility.has_collection(COLLECTION_NAME):
        return []
    collection = Collection(COLLECTION_NAME)
    collection.load()
    results = collection.search([embed(query)], "embedding", {"metric_type": "IP", "params": {}}, limit=limit,
                                expr=f"knowledge_base_id == {knowledge_base_id}", output_fields=["object_key", "content"])
    return [{"document_object_key": hit.entity.get("object_key"), "content": hit.entity.get("content"), "score": hit.score} for hit in results[0]]
