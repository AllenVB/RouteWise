"""
Rota ve çözüm metrikleri.

Faz 2'de amaç toplam mesafedir. Araç başına TL maliyet (yakıt, sürücü, sabit
gider, öncelik cezası) Faz 3'te bu modüle eklenir. Metrikler tek yerde
hesaplanır ki optimize çözüm ile baseline aynı terazide tartılsın.
"""

from __future__ import annotations

from dataclasses import dataclass
from itertools import pairwise

from .model import DEPOT, Problem, Route, Solution


@dataclass(frozen=True)
class Leg:
    to: int                   # problem indeksi; DEPOT = depoya dönüş
    distance_m: float
    duration_s: float


@dataclass(frozen=True)
class RouteMetrics:
    legs: tuple[Leg, ...]     # depo -> ilk durak, ..., son durak -> depo
    distance_m: float
    travel_s: float
    service_s: float
    load_kg: float

    @property
    def duration_s(self) -> float:
        return self.travel_s + self.service_s


EMPTY = RouteMetrics(legs=(), distance_m=0.0, travel_s=0.0, service_s=0.0, load_kg=0.0)


def route_metrics(problem: Problem, route: Route) -> RouteMetrics:
    if not route.stops:
        return EMPTY   # boş rota depodan çıkmaz
    path = [DEPOT, *route.stops, DEPOT]
    legs = tuple(
        Leg(b, float(problem.distance[a, b]), float(problem.duration[a, b]))
        for a, b in pairwise(path)
    )
    return RouteMetrics(
        legs=legs,
        distance_m=sum(leg.distance_m for leg in legs),
        travel_s=sum(leg.duration_s for leg in legs),
        service_s=sum(problem.stop(i).service_time_s for i in route.stops),
        load_kg=sum(problem.stop(i).demand_kg for i in route.stops),
    )


def total_distance(problem: Problem, solution: Solution) -> float:
    return sum(route_metrics(problem, r).distance_m for r in solution.routes)
