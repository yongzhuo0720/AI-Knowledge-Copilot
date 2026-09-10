from pydantic import BaseModel, Field


class ParseDocumentRequest(BaseModel):
    object_key: str = Field(min_length=1, max_length=512)
    filename: str = Field(min_length=1, max_length=255)
    content_type: str = Field(min_length=1, max_length=128)


class ParseTaskResponse(BaseModel):
    task_id: str
    object_key: str
    status: str
