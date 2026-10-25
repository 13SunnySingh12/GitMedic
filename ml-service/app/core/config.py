import os
from functools import lru_cache
from pathlib import Path

from pydantic import Field, ValidationError
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # GITMEDIC_ENV_FILE picks another env file (e.g. .env.supabase), as in the backend.
    model_config = SettingsConfigDict(
        env_file=os.environ.get("GITMEDIC_ENV_FILE", ".env"), extra="ignore"
    )

    ml_db_dsn: str
    internal_api_token: str = Field(min_length=32)
    model_dir: Path = Path("./data/models")
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    duplicate_threshold: float = Field(0.80, ge=0, le=1)
    duplicate_top_k: int = Field(5, ge=1, le=20)
    llm_enabled: bool = False
    llm_provider: str = "groq"
    llm_model: str = ""
    groq_api_key: str = ""
    llm_timeout_seconds: float = 20


@lru_cache
def get_settings() -> Settings:
    try:
        return Settings()  # type: ignore[call-arg]
    except ValidationError as e:
        names = ", ".join(str(err["loc"][0]).upper() for err in e.errors())
        # Fail fast naming the variables, never echoing their values (QA-CFG-01).
        raise SystemExit(f"Invalid or missing required environment variable(s): {names}") from None
