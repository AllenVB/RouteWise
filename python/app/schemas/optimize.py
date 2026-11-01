"""
`/internal/optimize` istek/yanıt modelleri.

Backend matrisi kendi cache'inden okuyup gönderir (mimari §12, adım 3–4);
Python servisi matrisi yeniden hesaplamaz ve iş verisi tutmaz.
"""

from __future__ import annotations

import math

from pydantic import Field, model_validator

from ..matrix.builder import MAX_NODES
from ..vrp.metaheuristic.simulated_annealing import AnnealParams
from ..vrp.model import CostParams, Problem, Stop, Vehicle
from ..vrp.solve import Algorithm
from .base import CamelModel


class StopIn(CamelModel):
    id: int = Field(..., description="Kargo kimliği — yanıtta aynen döner.")
    node_id: int
    demand_kg: float = Field(..., gt=0)
    service_time_s: float = Field(0.0, ge=0)
    priority: int = Field(0, ge=0, le=10,
                          description="Faz 3 amaç fonksiyonunda ceza katsayısıyla kullanılır.")


MAX_VEHICLES = 50


class VehicleIn(CamelModel):
    id: int
    capacity_kg: float = Field(..., gt=0)
    # Açık alias: otomatik üretici rakamdan sonraki harfi büyütür ("fuelLPer100Km").
    fuel_l_per_100km: float = Field(0.0, ge=0, alias="fuelLPer100km")
    fixed_cost_per_day: float = Field(0.0, ge=0)


class CostParamsIn(CamelModel):
    """Verilmezse amaç toplam mesafedir (Faz 2 davranışı)."""

    fuel_price_per_l: float = Field(..., gt=0)
    driver_cost_per_hour: float = Field(..., ge=0)
    priority_penalty: float = Field(0.0, ge=0, description="TL / (öncelik × varış saati).")


class WarmRouteIn(CamelModel):
    vehicle_id: int
    stop_ids: list[int] = Field(..., description="Önceki koşudaki sırasıyla kargo kimlikleri.")


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
    vehicles: list[VehicleIn] = Field(..., min_length=1, max_length=MAX_VEHICLES)
    matrix: MatrixIn
    cost_params: CostParamsIn | None = None
    random_seed: int | None = Field(
        None, ge=0, lt=2**31,
        description="Yalnızca ROUTEWISE kullanır; verilmezse üretilir ve yanıtta döner.")
    iterations: int = Field(20_000, ge=0, le=500_000, description="SA iterasyon bütçesi.")
    max_route_duration_s: float | None = Field(
        None, gt=0, description="Çalışma günü: rota süresi (yol + teslim + dönüş) bunu aşamaz.")
    warm_start: list[WarmRouteIn] | None = Field(
        None, description="Önceki uygulanan koşu; yalnızca ROUTEWISE. Bilinmeyen kargolar yok sayılır.")

    @model_validator(mode="after")
    def _tutarli(self) -> OptimizeRequest:
        ids = [s.id for s in self.stops]
        if len(set(ids)) != len(ids):
            raise ValueError("stops tekrar eden kargo kimliği içeriyor")
        vids = [v.id for v in self.vehicles]
        if len(set(vids)) != len(vids):
            raise ValueError("vehicles tekrar eden araç kimliği içeriyor")
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
            vehicles=[Vehicle(id=v.id, capacity_kg=v.capacity_kg, fuel_l_per_100km=v.fuel_l_per_100km,
                              fixed_cost_per_day=v.fixed_cost_per_day) for v in self.vehicles],
            costs=None if self.cost_params is None else CostParams(
                fuel_price_per_l=self.cost_params.fuel_price_per_l,
                driver_cost_per_hour=self.cost_params.driver_cost_per_hour,
                priority_penalty=self.cost_params.priority_penalty),
            max_duration_s=self.max_route_duration_s,
        )

    def anneal_params(self) -> AnnealParams:
        return AnnealParams(iterations=self.iterations)

    def warm_routes(self) -> list[list[int]] | None:
        """Araç sırasıyla hizalı, problem indeksli önceki rotalar."""
        if self.warm_start is None:
            return None
        index_of = {s.id: i for i, s in enumerate(self.stops, start=1)}
        by_vehicle = {w.vehicle_id: w.stop_ids for w in self.warm_start}
        return [[index_of[sid] for sid in by_vehicle.get(v.id, []) if sid in index_of]
                for v in self.vehicles]


class StopOut(CamelModel):
    stop_id: int
    node_id: int
    sequence_no: int = Field(..., description="1'den başlar; depo listede yer almaz.")
    arrival_s: float = Field(..., description="Depodan çıkıştan bu durağa varışa geçen süre; ETA'nın temeli.")
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
    fuel_l: float
    cost_try: float | None = Field(..., description="Yakıt + sürücü + sabit gider; maliyet modeli yoksa null.")


class SolutionOut(CamelModel):
    algorithm: str
    routes: list[RouteOut]
    total_distance_m: float
    total_duration_s: float
    vehicle_count: int = Field(..., description="En az bir durağı olan araç sayısı.")
    computation_time_ms: float
    total_fuel_l: float
    total_cost_try: float | None = Field(..., description="Gerçek para: yakıt + sürücü + sabit gider.")
    priority_penalty: float | None = Field(..., description="Amaçtaki ceza terimi; para değildir.")
    objective: float = Field(..., description="Küçültülen değer: maliyet + ceza; maliyet yoksa mesafe.")


class UnassignedOut(CamelModel):
    stop_id: int
    reason: str


class OptimizeResponse(CamelModel):
    random_seed: int | None = Field(..., description="Sonucu tekrar üretmek için; tohumsuz algoritmada null.")
    result: SolutionOut
    baseline: SolutionOut | None = Field(
        ..., description="Naive — aynı durak kümesiyle, her koşuda. Kurulamazsa null; sebep baselineError'da.")
    baseline_error: str | None = None
    unassigned: list[UnassignedOut] = Field(
        ..., description="Rotaya alınamayan duraklar: UNREACHABLE (depoyla gidiş-dönüş yolu yok) "
                         "ya da EXCEEDS_WORKDAY (tek başına bile çalışma gününe sığmıyor).")
