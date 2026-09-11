from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "ai-knowledge-copilot-ai-service"
    app_version: str = "0.0.1"
    host: str = "0.0.0.0"
    port: int = 8000
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "change-me-minio"
    minio_bucket: str = "ai-copilot"
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_database: str = "ai_copilot"
    mysql_user: str = "ai_copilot"
    mysql_password: str = ""
    task_store_enabled: bool = False
    task_max_retries: int = 3
    task_retry_backoff_seconds: float = 0.1
    task_recovery_interval_seconds: int = 5
    task_processing_stale_seconds: int = 300
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v4"
    chat_base_url: str = "https://api.deepseek.com"
    chat_api_key: str = ""
    chat_model: str = "deepseek-chat"

    model_config = SettingsConfigDict(env_prefix="AI_SERVICE_", extra="ignore")


settings = Settings()
