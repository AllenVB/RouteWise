"""Servis yapılandırması — ortam değişkenlerinden okunur."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ROUTEWISE_", extra="ignore")

    # Yol grafı dosyası. Faz 0'daki dataprep `export` adımı üretir.
    graph_path: str = "/data/graph/bandirma-graph.npz"

    # Graf bulunamazsa servis başlamaz (fail fast). Testlerde kapatılabilir.
    require_graph: bool = True

    service_name: str = "routewise-optimizer"


settings = Settings()
