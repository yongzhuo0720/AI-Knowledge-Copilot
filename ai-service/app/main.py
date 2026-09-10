from fastapi import FastAPI

from app.processing import submit_parse_task
from app.schemas import ParseDocumentRequest, ParseTaskResponse
from app.settings import settings

app = FastAPI(title=settings.app_name, version=settings.app_version)


@app.get("/api/v1/health")
def health() -> dict[str, object]:
    return {
        "code": "0",
        "message": "success",
        "data": {"status": "UP", "service": settings.app_name},
    }


@app.post("/api/v1/document-processing/tasks", response_model=dict[str, object])
def create_parse_task(request: ParseDocumentRequest) -> dict[str, object]:
    task = submit_parse_task(request)
    return {
        "code": "0",
        "message": "success",
        "data": task.model_dump(),
    }
