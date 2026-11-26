"""
Rota geometrisi ucu — haritada çizim için yol üzerindeki ara noktalar.

Matris yalnızca durak çiftleri arasındaki mesafeyi verir; haritada düz çizgi
yerine gerçek yolu çizmek için ardışık her durak çifti arasında en kısa yolun
düğümleri gerekir. Ağırlık matrisle aynıdır (mesafe), dolayısıyla çizilen yol
optimizasyonun hesapladığı yoldur.
"""

from __future__ import annotations

import logging
from itertools import pairwise

from fastapi import APIRouter, HTTPException

from ..pathfinding.base import Timer, Weight
from ..pathfinding.dijkstra import shortest_path
from ..schemas.geometry import GeometryRequest, GeometryResponse, LegGeometry

log = logging.getLogger(__name__)
router = APIRouter(prefix="/internal", tags=["servisler arası"])


@router.post("/route-geometry", response_model=GeometryResponse, response_model_by_alias=True)
def route_geometry(req: GeometryRequest) -> GeometryResponse:
    """Ardışık düğüm çiftleri arasında en kısa yolun koordinatları."""
    from ..main import get_graph

    graph = get_graph()
    legs: list[LegGeometry] = []

    with Timer() as timer:
        for a, b in pairwise(req.node_ids):
            try:
                r = shortest_path(graph, a, b, Weight.DISTANCE)
            except KeyError as e:
                raise HTTPException(status_code=422, detail=str(e)) from e
            if not r.found:
                raise HTTPException(status_code=422, detail=f"yol yok: {a} -> {b}")

            idx = [graph.index_of(n) for n in r.path]
            legs.append(LegGeometry(
                from_node_id=a,
                to_node_id=b,
                length_m=round(r.length_m, 2),
                duration_s=round(r.duration_s, 1),
                # 6 ondalık ~ 0,1 m: haritada fark edilmez, yanıtı küçültür.
                coordinates=[(round(float(graph.node_lon[i]), 6), round(float(graph.node_lat[i]), 6))
                             for i in idx],
            ))

    log.info("geometri: %d bacak, %.0f ms", len(legs), timer.ms)
    return GeometryResponse(
        legs=legs,
        total_length_m=round(sum(leg.length_m for leg in legs), 2),
        computation_time_ms=round(timer.ms, 2),
    )
