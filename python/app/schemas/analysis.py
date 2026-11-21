"""Deney ve analiz uçlarının istek/yanıt modelleri."""

from __future__ import annotations

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
