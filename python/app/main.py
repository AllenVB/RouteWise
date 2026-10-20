"""
RouteWise Python servisi.

İki modül barındırır:
  optimizer/  rota optimizasyon ve karşılaştırma algoritmaları
  analytics/  tasarruf istatistikleri ve rapor figürleri

İş verisi açısından durumsuzdur: teslimat, araç ve rota verisi her zaman
istekle gelir, veritabanına erişilmez. Tek istisna yol grafıdır; değişmeyen
referans veri olarak açılışta dosyadan yüklenir.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import matrix as matrix_api
from .config import settings
from .graph.loader import load_graph
from .graph.model import RoadGraph

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)
log = logging.getLogger("routewise")

# Açılışta yüklenen graf. Modül düzeyinde tutulur; istekler arasında paylaşılır.
_graph: RoadGraph | None = None


def get_graph() -> RoadGraph:
    if _graph is None:
        raise RuntimeError("yol grafı yüklenmedi")
    return _graph


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _graph
    try:
        _graph = load_graph(settings.graph_path)
    except (FileNotFoundError, ValueError) as exc:
        if settings.require_graph:
            # Sessizce başlamak yerine açıkça patla: grafsız servis hiçbir işe
            # yaramaz ve sorunun nerede olduğu saatler sonra anlaşılır.
            log.error("yol grafı yüklenemedi: %s", exc)
            raise
        log.warning("yol grafı yüklenmedi (require_graph kapalı): %s", exc)
        _graph = None
    yield
    _graph = None


app = FastAPI(
    title="RouteWise Optimizer",
    description="Rota optimizasyonu, algoritma karşılaştırması ve tasarruf analizi",
    version="0.1.0",
    lifespan=lifespan,
)


app.include_router(matrix_api.router)


@app.get("/health", tags=["sistem"])
def health() -> dict:
    """Servis ve yol grafı durumu. Graf yoksa sağlıksız sayılır."""
    if _graph is None:
        return {"status": "degraded", "graph": None}
    return {"status": "ok", "service": settings.service_name, "graph": _graph.summary()}
