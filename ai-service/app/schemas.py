from pydantic import AliasChoices, BaseModel, Field


class ParseDocumentRequest(BaseModel):
    object_key: str = Field(
        min_length=1,
        max_length=512,
        validation_alias=AliasChoices("object_key", "objectKey"),
    )
    filename: str = Field(min_length=1, max_length=255)
    content_type: str = Field(
        min_length=1,
        max_length=128,
        validation_alias=AliasChoices("content_type", "contentType"),
    )


class ParseTaskResponse(BaseModel):
    task_id: str
    object_key: str
    status: str
