"""`/internal/compare/path` istek/yanıt modelleri — Seviye 1 karşılaştırması."""

from __future__ import annotations

from typing import Literal

from pydantic import Field, model_validator

from .base import CamelModel
from .optimize import ProblemRequest, SolutionOut, UnassignedOut

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


# --- Seviye 2: tur sırası -----------------------------------------------------


class TourCompareRequest(ProblemRequest):
    """Tek araç: kesin optimum (DFS_EXHAUSTIVE) çok araçta denenemez."""

    repeats: int = Field(5, ge=1, le=MAX_REPEATS,
                         description="Her algoritma bu kadar koşar; ROUTEWISE her tekrarda başka tohumla.")

    @model_validator(mode="after")
    def _tek_arac(self) -> TourCompareRequest:
        if len(self.vehicles) != 1:
            raise ValueError("tur karşılaştırması tek araçla yapılır")
        return self


class TourAlgorithmOut(CamelModel):
    algorithm: str
    feasible: bool
    error: str | None = Field(None, description="Olurlu tur kurulamadıysa sebebi.")
    solution: SolutionOut | None
    computation_time_ms: float = Field(..., description="Koşuların medyanı.")
    gap_pct: float | None = Field(None, description="Referansa göre amaçta sapma (%).")
    random_seed: int | None = Field(None, description="Yalnızca ROUTEWISE: raporlanan (medyan) koşunun tohumu.")
    objective_min: float | None = Field(None, description="Tekrarlar içinde en iyi amaç.")
    objective_max: float | None = Field(None, description="Tekrarlar içinde en kötü amaç.")
    explored: int | None = Field(None, description="Yalnızca DFS_EXHAUSTIVE: açılan arama düğümü.")


class TourCompareResponse(CamelModel):
    repeats: int
    seeds: list[int]
    reference_algorithm: str | None
    reference_is_optimal: bool = Field(
        ..., description="true: referans kesin optimum; false: n > 10, referans bulunan en iyi çözüm.")
    exhaustive_skipped: str | None
    results: list[TourAlgorithmOut]
    unassigned: list[UnassignedOut]
