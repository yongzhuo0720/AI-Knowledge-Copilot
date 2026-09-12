import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import BackgroundTasks, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
import json

from app.indexing import count_chunks, retrieve_chunks
from app.answering import answer_question, stream_answer_question
from app.processing import get_parse_task, process_parse_task, recover_parse_tasks, retry_parse_task, submit_parse_task
from app.schemas import AnswerRequest, ParseDocumentRequest, RetrievalRequest
from app.settings import settings

@asynccontextmanager
async def lifespan(application: FastAPI):
    recovery_task = asyncio.create_task(_recover_tasks_loop())
    try:
        yield
    finally:
        recovery_task.cancel()
        await asyncio.gather(recovery_task, return_exceptions=True)


app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)
logger = logging.getLogger(__name__)


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
        "data": retrieve_chunks(request.knowledge_base_id, request.query, request.limit),
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
    return {"code": "0", "message": "success", "data": answer_question(request.knowledge_base_id, request.question, history)}


@app.post("/api/v1/answers/stream")
def stream_knowledge_question(request: AnswerRequest) -> StreamingResponse:
    history = [message.model_dump() for message in request.history]

    def events():
        for item in stream_answer_question(request.knowledge_base_id, request.question, history):
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
