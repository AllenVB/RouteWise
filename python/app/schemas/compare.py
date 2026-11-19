"""`/internal/compare/path` istek/yanıt modelleri — Seviye 1 karşılaştırması."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from .base import CamelModel

MAX_REPEATS = 50


class PathCompareRequest(CamelModel):
    from_node_id: int
    to_node_id: int
    weight: Literal["distance", "duration"] = "distance"
    repeats: int = Field(5, ge=1, le=MAX_REPEATS,
                         description="Her algoritma bu kadar koşar; süre medyanı raporlanır.")


class PathAlgorithmOut(CamelModel):
    algorithm: Literal["DIJKSTRA", "ASTAR", "BFS", "DFS"]
    found: bool
    length_m: float
    duration_s: float
    edge_count: int
    nodes_visited: int
    computation_time_ms: float = Field(..., description="Koşuların medyanı.")
    deviation_pct: float | None = Field(
        None, description="Seçilen ağırlıkta Dijkstra'dan sapma; yol yoksa boş.")
    same_path_as_reference: bool
    path: list[int]
    coordinates: list[tuple[float, float]] = Field(
        ..., description="[boylam, enlem] çiftleri — GeoJSON sırası.")


class PathCompareResponse(CamelModel):
    from_node_id: int
    to_node_id: int
    weight: Literal["distance", "duration"]
    repeats: int
    results: list[PathAlgorithmOut]
