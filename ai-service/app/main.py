from fastapi import FastAPI

from app.settings import settings

app = FastAPI(title=settings.app_name, version=settings.app_version)


@app.get("/api/v1/health")
def health() -> dict[str, object]:
    return {
        "code": "0",
        "message": "success",
        "data": {"status": "UP", "service": settings.app_name},
    }
