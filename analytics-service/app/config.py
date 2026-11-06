"""Environment-driven configuration for the analytics / AI service."""

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "auditiq-analytics-service"
    version: str = "0.4.0"
    database_url: str = Field(
        default="postgresql+psycopg://auditiq:auditiq@localhost:5432/auditiq",
        alias="ANALYTICS_DATABASE_URL",
    )
    service_key: str = Field(default="", alias="ANALYTICS_SERVICE_KEY")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    data_dir: Path = Field(default=Path("data"), alias="PIPELINE_DATA_DIR")
    # 0 disables the scheduler; otherwise a GENERATED run is triggered every N minutes.
    schedule_minutes: int = Field(default=0, ge=0, alias="PIPELINE_SCHEDULE_MINUTES")
    scheduled_transaction_count: int = Field(default=20_000, alias="PIPELINE_SCHEDULED_TRANSACTIONS")


@lru_cache
def get_settings() -> Settings:
    return Settings()
