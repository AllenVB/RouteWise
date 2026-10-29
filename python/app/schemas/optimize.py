"""
`/internal/optimize` istek/yanıt modelleri.

Backend matrisi kendi cache'inden okuyup gönderir (mimari §12, adım 3–4);
Python servisi matrisi yeniden hesaplamaz ve iş verisi tutmaz.
"""

from __future__ import annotations

import math

from pydantic import Field, model_validator

from ..matrix.builder import MAX_NODES
from ..vrp.model import Problem, Stop, Vehicle
from ..vrp.solve import Algorithm
from .base import CamelModel


class StopIn(CamelModel):
    id: int = Field(..., description="Kargo kimliği — yanıtta aynen döner.")
    node_id: int
    demand_kg: float = Field(..., gt=0)
    service_time_s: float = Field(0.0, ge=0)
    priority: int = Field(0, ge=0, le=10,
                          description="Faz 3 amaç fonksiyonunda ceza katsayısıyla kullanılır.")


class VehicleIn(CamelModel):
    id: int
    capacity_kg: float = Field(..., gt=0)


class MatrixIn(CamelModel):
    """`/internal/matrix` yanıtıyla aynı biçim: satır/sütunlar `nodeIds` sırasında."""

    node_ids: list[int] = Field(..., min_length=1, max_length=MAX_NODES)
    distance_m: list[list[float | None]]
    duration_s: list[list[float | None]]

    @model_validator(mode="after")
    def _kare_ve_gecerli(self) -> MatrixIn:
        n = len(self.node_ids)
        if len(set(self.node_ids)) != n:
            raise ValueError("nodeIds tekrar eden düğüm içeriyor")
        for ad, m in (("distanceM", self.distance_m), ("durationS", self.duration_s)):
            if len(m) != n or any(len(r) != n for r in m):
                raise ValueError(f"{ad} {n}x{n} olmalı")
            # NaN karşılaştırmaları hep False döner: en yakın komşu seçimini
            # sessizce bozar. Yol yoksa null gönderilir, sayı değil.
            if any(v is not None and not (math.isfinite(v) and v >= 0) for r in m for v in r):
                raise ValueError(f"{ad} yalnızca sonlu, negatif olmayan değer veya null içerebilir")
        return self


class OptimizeRequest(CamelModel):
    algorithm: Algorithm = Algorithm.GREEDY_NN
    depot_node_id: int
    stops: list[StopIn] = Field(
        ..., min_length=1, max_length=MAX_NODES - 1,
        description="Liste sırası naive baseline'ın ziyaret sırasıdır (oluşturulma sırası).")
    vehicles: list[VehicleIn] = Field(..., min_length=1, max_length=1,
                                      description="Faz 2: tek araç.")
    matrix: MatrixIn

    @model_validator(mode="after")
    def _tutarli(self) -> OptimizeRequest:
        ids = [s.id for s in self.stops]
        if len(set(ids)) != len(ids):
            raise ValueError("stops tekrar eden kargo kimliği içeriyor")
        bilinen = set(self.matrix.node_ids)
        eksik = sorted({self.depot_node_id, *(s.node_id for s in self.stops)} - bilinen)
        if eksik:
            raise ValueError(f"matriste olmayan düğümler: {eksik[:10]}")
        return self

    def to_problem(self) -> Problem:
        return Problem.from_node_matrix(
            node_ids=self.matrix.node_ids,
            distance_m=self.matrix.distance_m,
            duration_s=self.matrix.duration_s,
            depot_node_id=self.depot_node_id,
            stops=[Stop(id=s.id, node_id=s.node_id, demand_kg=s.demand_kg,
                        service_time_s=s.service_time_s, priority=s.priority)
                   for s in self.stops],
            vehicles=[Vehicle(id=v.id, capacity_kg=v.capacity_kg) for v in self.vehicles],
        )


class StopOut(CamelModel):
    stop_id: int
    node_id: int
    sequence_no: int = Field(..., description="1'den başlar; depo listede yer almaz.")
    distance_from_prev_m: float
    duration_from_prev_s: float


class RouteOut(CamelModel):
    vehicle_id: int
    stops: list[StopOut]
    return_distance_m: float
    return_duration_s: float
    total_distance_m: float
    travel_time_s: float
    service_time_s: float
    total_duration_s: float = Field(..., description="Yol süresi + servis süreleri.")
    total_load_kg: float


class SolutionOut(CamelModel):
    algorithm: str
    routes: list[RouteOut]
    total_distance_m: float
    total_duration_s: float
    vehicle_count: int = Field(..., description="En az bir durağı olan araç sayısı.")
    computation_time_ms: float


class UnassignedOut(CamelModel):
    stop_id: int
    reason: str


class OptimizeResponse(CamelModel):
    result: SolutionOut
    baseline: SolutionOut = Field(..., description="Naive — aynı durak kümesiyle, her koşuda.")
    unassigned: list[UnassignedOut] = Field(
        ..., description="Rotaya alınamayan duraklar; sebep UNREACHABLE: depoyla gidiş-dönüş yolu yok.")
