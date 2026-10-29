"""`/internal/route-geometry` istek/yanıt modelleri."""

from __future__ import annotations

from pydantic import Field

from ..matrix.builder import MAX_NODES
from .base import CamelModel


class GeometryRequest(CamelModel):
    node_ids: list[int] = Field(
        ..., min_length=2, max_length=MAX_NODES + 1,
        description="Ziyaret sırasıyla düğümler; depo başta ve sonda.")


class LegGeometry(CamelModel):
    from_node_id: int
    to_node_id: int
    length_m: float
    duration_s: float
    coordinates: list[tuple[float, float]] = Field(
        ..., description="[boylam, enlem] çiftleri — GeoJSON sırası.")


class GeometryResponse(CamelModel):
    legs: list[LegGeometry]
    total_length_m: float
    computation_time_ms: float
