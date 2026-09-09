from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "ai-knowledge-copilot-ai-service"
    app_version: str = "0.0.1"
    host: str = "0.0.0.0"
    port: int = 8000

    model_config = SettingsConfigDict(env_prefix="AI_SERVICE_", extra="ignore")


settings = Settings()
