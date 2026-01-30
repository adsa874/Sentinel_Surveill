"""Application configuration."""

from pydantic_settings import BaseSettings
from functools import lru_cache
import secrets


class Settings(BaseSettings):
    """Application settings."""

    # App settings
    app_name: str = "Sentinel"
    app_version: str = "1.0.0"
    debug: bool = True

    # Database
    database_url: str = "sqlite:///./sentinel.db"

    # Security
    secret_key: str = secrets.token_urlsafe(32)
    api_key_header: str = "X-API-Key"

    # Server
    host: str = "0.0.0.0"
    port: int = 8000

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


@lru_cache()
def get_settings() -> Settings:
    """Get cached settings instance."""
    return Settings()
