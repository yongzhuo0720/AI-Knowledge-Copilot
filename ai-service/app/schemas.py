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
    knowledge_base_id: int = Field(
        gt=0,
        validation_alias=AliasChoices("knowledge_base_id", "knowledgeBaseId"),
    )
    replace_existing: bool = Field(
        default=False,
        validation_alias=AliasChoices("replace_existing", "replaceExisting"),
    )


class ParseTaskResponse(BaseModel):
    task_id: str
    knowledge_base_id: int
    object_key: str
    filename: str
    content_type: str
    status: str
    failure_reason: str | None = None
    retry_count: int = 0
    max_retries: int = 3
    replace_existing: bool = False


class RetrievalRequest(BaseModel):
    knowledge_base_id: int = Field(gt=0, validation_alias=AliasChoices("knowledge_base_id", "knowledgeBaseId"))
    query: str = Field(min_length=1, max_length=1000)
    limit: int = Field(default=5, ge=1, le=20)
    # 检索模式：vector 纯向量 / keyword 纯 BM25 / hybrid 混合（不传则用服务端配置）
    mode: str | None = Field(default=None, pattern="^(vector|keyword|hybrid)$")


class RetrievalChunk(BaseModel):
    document_object_key: str
    content: str
    score: float
    retriever: str | None = None


class AnswerRequest(BaseModel):
    knowledge_base_id: int = Field(gt=0, validation_alias=AliasChoices("knowledge_base_id", "knowledgeBaseId"))
    question: str = Field(min_length=1, max_length=1000)
    history: list["ChatHistoryMessage"] = Field(default_factory=list, max_length=30)
    mode: str | None = Field(default=None, pattern="^(vector|keyword|hybrid)$")


class AgentRequest(AnswerRequest):
    pass


class ChatHistoryMessage(BaseModel):
    role: str = Field(pattern="^(USER|ASSISTANT|user|assistant)$")
    content: str = Field(min_length=1, max_length=10000)
