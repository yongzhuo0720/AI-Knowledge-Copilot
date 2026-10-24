import asyncio
import hmac
import logging
from contextlib import asynccontextmanager

from fastapi import BackgroundTasks, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
import json

from app.document_blocks import extract_blocks
from app.indexing import count_chunks, delete_chunks, delete_knowledge_base_chunks, retrieve_chunks
from app.answering import answer_question, stream_answer_question
from app.agent import run_knowledge_agent
from app.processing import get_parse_task, process_parse_task, recover_parse_tasks, retry_parse_task, submit_parse_task
from app.schemas import AgentRequest, AnswerRequest, ParseDocumentRequest, RetrievalRequest
from app.settings import settings
from app.storage import download_object

# 预览响应的体量上限。预览读的是「整个原文的结构化内容」，一篇几百页的 PDF
# 全量返回会把浏览器和内存一起拖垮；截断是显式契约（truncated=true），
# 前端提示「内容过长，仅展示前一部分」。
PREVIEW_MAX_SECTIONS = 60
PREVIEW_MAX_CHARS = 20000

@asynccontextmanager
async def lifespan(application: FastAPI):
    if not settings.internal_token:
        logger.warning(
            "AI_SERVICE_INTERNAL_TOKEN is not set; internal API authentication is DISABLED"
        )
    recovery_task = asyncio.create_task(_recover_tasks_loop())
    try:
        yield
    finally:
        recovery_task.cancel()
        await asyncio.gather(recovery_task, return_exceptions=True)


app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)
logger = logging.getLogger(__name__)

INTERNAL_TOKEN_HEADER = "X-Internal-Token"
_PUBLIC_PATHS = frozenset({"/api/v1/health"})


@app.middleware("http")
async def require_internal_token(request: Request, call_next):
    """除健康检查外，所有接口只允许持有内部 Token 的调用方（backend）访问。

    健康检查必须放行：容器 healthcheck 不带业务凭证，也不该因为鉴权配置而误判服务已死。
    Token 未配置时不做校验，这是为了让本地开发和测试不必先配环境变量；
    生产环境漏配会在启动日志里看到明确警告，而不是静默裸奔。
    """
    expected = settings.internal_token
    if expected and request.url.path not in _PUBLIC_PATHS:
        provided = request.headers.get(INTERNAL_TOKEN_HEADER, "")
        if not hmac.compare_digest(provided.encode("utf-8", "ignore"), expected.encode("utf-8")):
            return JSONResponse(status_code=401, content={"detail": "invalid internal token"})
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, exception: RequestValidationError) -> JSONResponse:
    logger.warning("document processing validation failed: %s", exception.errors())
    return JSONResponse(status_code=422, content={"detail": exception.errors()})


@app.get("/api/v1/health")
def health() -> dict[str, object]:
    return {
        "code": "0",
        "message": "success",
        "data": {"status": "UP", "service": settings.app_name},
    }


@app.post("/api/v1/document-processing/tasks", response_model=dict[str, object])
def create_parse_task(request: ParseDocumentRequest, background_tasks: BackgroundTasks) -> dict[str, object]:
    task = submit_parse_task(request)
    background_tasks.add_task(process_parse_task, task.task_id, request)
    return {
        "code": "0",
        "message": "success",
        "data": task.model_dump(),
    }


@app.get("/api/v1/document-processing/tasks/{task_id}", response_model=dict[str, object])
def get_parse_task_status(task_id: str) -> dict[str, object]:
    task = get_parse_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="parse task not found")
    return {
        "code": "0",
        "message": "success",
        "data": task.model_dump(),
    }


@app.post("/api/v1/document-processing/tasks/{task_id}/retry", response_model=dict[str, object])
def retry_parse_task_endpoint(task_id: str, background_tasks: BackgroundTasks) -> dict[str, object]:
    task = retry_parse_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="parse task not found")
    if task.status != "ACCEPTED":
        raise HTTPException(status_code=409, detail="only failed parse tasks can be retried")
    background_tasks.add_task(process_parse_task, task.task_id)
    return {"code": "0", "message": "success", "data": task.model_dump()}


@app.post("/api/v1/retrieval/search", response_model=dict[str, object])
def search_knowledge(request: RetrievalRequest) -> dict[str, object]:
    return {
        "code": "0",
        "message": "success",
        "data": retrieve_chunks(request.knowledge_base_id, request.query, request.limit, request.mode),
    }


@app.delete("/api/v1/knowledge-bases/{knowledge_base_id}/index", response_model=dict[str, object])
def delete_knowledge_base_index(knowledge_base_id: int) -> dict[str, object]:
    """供 backend 在删除知识库前清理 Milvus 向量，幂等。"""
    delete_knowledge_base_chunks(knowledge_base_id)
    return {
        "code": "0",
        "message": "success",
        "data": {"knowledge_base_id": knowledge_base_id, "deleted": True},
    }


@app.delete("/api/v1/knowledge-bases/{knowledge_base_id}/documents/index", response_model=dict[str, object])
def delete_document_index(
        knowledge_base_id: int,
        object_key: str = Query(min_length=1, max_length=512),
) -> dict[str, object]:
    """供 backend 在删除单篇文档前清理 Milvus 向量，幂等。"""
    delete_chunks(knowledge_base_id, object_key)
    return {
        "code": "0",
        "message": "success",
        "data": {"knowledge_base_id": knowledge_base_id, "object_key": object_key, "deleted": True},
    }


@app.get("/api/v1/knowledge-bases/{knowledge_base_id}/documents/preview", response_model=dict[str, object])
def preview_document(
        knowledge_base_id: int,
        object_key: str = Query(min_length=1, max_length=512),
) -> dict[str, object]:
    """把一篇文档的原文按小节结构读出来，供前端的「文档预览」。

    与 Agent 的 document_outline 同源：Milvus 的 collection 里没有标题链元数据，
    要展示人类可读的结构就只能重新下载原文走 extract_blocks。这里取的是
    **完整分节正文**（outline 只要标题），因为它服务的是阅读而不是检索。

    越权防线与 agent_tools 相同：object_key 是共享对象存储里的路径，必须先确认
    它确实属于当前知识库的前缀。backend 在调用前已做过行级归属校验，这里是兜底。
    """
    expected_prefix = f"kb/{knowledge_base_id}/"
    if (
        not object_key.startswith(expected_prefix)
        or len(object_key) <= len(expected_prefix)
        or ".." in object_key
        or "\\" in object_key
        or "//" in object_key
    ):
        raise HTTPException(status_code=403, detail="object key does not belong to this knowledge base")
    try:
        content = download_object(object_key)
    except Exception as exception:
        raise HTTPException(status_code=502, detail=f"failed to read object: {exception}") from exception
    filename = object_key.rsplit("/", 1)[-1]
    try:
        # content_type 留空、按文件名后缀路由 —— 与 document_outline 的理由一致：
        # object_key 末尾保留了原始扩展名，足够 extract_blocks 判断类型。
        blocks = extract_blocks(content, "", filename)
    except Exception as exception:
        raise HTTPException(status_code=422, detail=f"failed to extract document: {exception}") from exception

    sections: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    total_chars = 0
    truncated = False
    for block in blocks:
        text = (block.text or "").strip()
        if not text:
            continue
        title_path = list(block.heading_path)
        # 相邻同属一节的块合并成一组：保持文档顺序，重复出现的标题链各成一组。
        if current is None or current["title_path"] != title_path:
            if len(sections) >= PREVIEW_MAX_SECTIONS:
                truncated = True
                break
            current = {"title_path": title_path, "blocks": []}
            sections.append(current)
        if total_chars + len(text) > PREVIEW_MAX_CHARS:
            truncated = True
            break
        current["blocks"].append(text)
        total_chars += len(text)

    return {
        "code": "0",
        "message": "success",
        "data": {
            "object_key": object_key,
            "name": filename,
            "section_count": len(sections),
            "block_count": sum(len(section["blocks"]) for section in sections),
            "truncated": truncated,
            "sections": sections,
        },
    }


@app.get("/api/v1/retrieval/stats", response_model=dict[str, object])
def retrieval_stats(knowledge_base_ids: str = Query(default="")) -> dict[str, object]:
    try:
        ids = [int(value) for value in knowledge_base_ids.split(",") if value.strip()]
    except ValueError as exception:
        raise HTTPException(status_code=422, detail="knowledge_base_ids must be comma-separated integers") from exception
    return {
        "code": "0",
        "message": "success",
        "data": {"indexed_chunk_count": count_chunks(ids)},
    }


@app.post("/api/v1/answers", response_model=dict[str, object])
def answer_knowledge_question(request: AnswerRequest) -> dict[str, object]:
    history = [message.model_dump() for message in request.history]
    return {
        "code": "0",
        "message": "success",
        "data": answer_question(request.knowledge_base_id, request.question, history, request.mode),
    }


@app.post("/api/v1/agents/knowledge", response_model=dict[str, object])
def run_knowledge_agent_endpoint(request: AgentRequest) -> dict[str, object]:
    history = [message.model_dump() for message in request.history]
    return {"code": "0", "message": "success", "data": run_knowledge_agent(request.knowledge_base_id, request.question, history)}


@app.post("/api/v1/answers/stream")
def stream_knowledge_question(request: AnswerRequest) -> StreamingResponse:
    history = [message.model_dump() for message in request.history]

    def events():
        for item in stream_answer_question(request.knowledge_base_id, request.question, history, request.mode):
            yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


async def _recover_tasks_loop() -> None:
    if not settings.task_store_enabled:
        return
    while True:
        try:
            await asyncio.to_thread(recover_parse_tasks)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("failed to recover document processing tasks")
        await asyncio.sleep(settings.task_recovery_interval_seconds)
