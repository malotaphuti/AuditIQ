"""Environment-driven configuration for the analytics / AI service."""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "auditiq-analytics-service"
    version: str = "0.1.0"
    database_url: str = Field(
        default="postgresql+psycopg://auditiq:auditiq@localhost:5432/auditiq",
        alias="ANALYTICS_DATABASE_URL",
    )
    service_key: str = Field(default="", alias="ANALYTICS_SERVICE_KEY")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")


@lru_cache
def get_settings() -> Settings:
    return Settings()
