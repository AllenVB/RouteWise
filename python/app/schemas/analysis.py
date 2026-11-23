"""Deney ve analiz uçlarının istek/yanıt modelleri."""

from __future__ import annotations

from datetime import date, datetime
from typing import Literal

from pydantic import Field, model_validator

from ..vrp.exhaustive import MAX_STOPS
from .base import CamelModel
from .compare import TourCompareResponse
from .optimize import CostParamsIn, StopIn, VehicleIn


class TourScenarioRequest(CamelModel):
    """Bir senaryo üret (tohumdan) ve tüm tur algoritmalarını üzerinde karşılaştır."""

    depot_node_id: int
    seed: int = Field(..., ge=0, lt=2**31)
    stop_count: int = Field(10, ge=3, le=MAX_STOPS,
                            description="En fazla 10: kesin optimum referans olsun diye.")
    radius_m: float = Field(3000.0, ge=500, le=15_000, description="Noktalar depoya bu kuş uçuşu mesafe içinde.")
    vehicle: VehicleIn
    cost_params: CostParamsIn
    max_route_duration_s: float | None = Field(None, gt=0)
    repeats: int = Field(3, ge=1, le=10)
    iterations: int = Field(20_000, ge=0, le=500_000)


class TourScenarioResponse(CamelModel):
    seed: int
    stops: list[StopIn]
    comparison: TourCompareResponse


class PairIn(CamelModel):
    candidate: str
    reference: str
    candidate_values: list[float] = Field(..., min_length=2)
    reference_values: list[float] = Field(..., min_length=2)

    @model_validator(mode="after")
    def _ayni_uzunluk(self) -> PairIn:
        if len(self.candidate_values) != len(self.reference_values):
            raise ValueError("candidateValues ve referenceValues aynı uzunlukta olmalı (senaryo başına bir çift)")
        return self


class PairedRequest(CamelModel):
    pairs: list[PairIn] = Field(..., min_length=1, max_length=20)
    alpha: float = Field(0.05, gt=0, lt=0.5)


class PairedOut(CamelModel):
    candidate: str
    reference: str
    n: int
    candidate_better: int
    ties: int
    candidate_worse: int
    mean_diff: float = Field(..., description="Aday - referans; negatif: aday daha ucuz.")
    median_diff: float
    mean_diff_pct: float
    wilcoxon_statistic: float | None
    wilcoxon_p: float = Field(..., description="Tek yönlü: H1 aday < referans.")
    t_statistic: float | None
    t_p: float
    significant: bool = Field(..., description="Wilcoxon p < alpha.")
    note: str | None


class PairedResponse(CamelModel):
    alpha: float
    results: list[PairedOut]


# --- Tasarruf ve rapor -------------------------------------------------------------


class PlanRowIn(CamelModel):
    plan_date: date
    optimized_cost_try: float = Field(..., ge=0)
    baseline_cost_try: float = Field(..., ge=0)
    optimized_distance_m: float = Field(..., ge=0)
    baseline_distance_m: float = Field(..., ge=0)
    vehicle_count: int = Field(0, ge=0)
    shipment_count: int = Field(0, ge=0)


class SavingsRequest(CamelModel):
    period: Literal["WEEK", "MONTH", "YEAR"]
    rows: list[PlanRowIn] = Field(..., max_length=20_000)


class BucketOut(CamelModel):
    start: date
    end: date
    plans: int
    shipments: int
    optimized_cost_try: float
    baseline_cost_try: float
    savings_try: float
    savings_pct: float | None
    optimized_distance_m: float
    baseline_distance_m: float
    distance_savings_pct: float | None


class SavingsResponse(CamelModel):
    period: Literal["WEEK", "MONTH", "YEAR"]
    buckets: list[BucketOut]
    total: BucketOut | None


class PathStatIn(CamelModel):
    algorithm: str
    runs: int
    median_deviation_pct: float
    max_deviation_pct: float
    median_nodes_visited: float
    median_time_ms: float


class ExperimentAlgorithmIn(CamelModel):
    algorithm: str
    scenarios: int
    median_gap_pct: float
    mean_gap_pct: float
    max_gap_pct: float
    optimal_count: int
    median_time_ms: float


class ExperimentTestIn(CamelModel):
    candidate: str
    reference: str
    n: int
    candidate_better: int
    ties: int
    mean_diff_pct: float
    wilcoxon_p: float
    t_p: float
    significant: bool


class ExperimentIn(CamelModel):
    id: int
    base_seed: int
    scenarios: int
    stop_count: int
    algorithms: list[ExperimentAlgorithmIn]
    tests: list[ExperimentTestIn]
    claim: str
    gaps: dict[str, list[float]] = Field(default_factory=dict, description="Algoritma -> senaryo başına sapma (%).")


class ExportRequest(CamelModel):
    format: Literal["XLSX", "PDF"]
    period: Literal["WEEK", "MONTH", "YEAR"]
    date_from: date
    date_to: date
    generated_at: datetime
    rows: list[PlanRowIn] = Field(..., max_length=20_000)
    path_stats: list[PathStatIn] = Field(default_factory=list)
    experiment: ExperimentIn | None = None
