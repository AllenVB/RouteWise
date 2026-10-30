"""
Rota ve çözüm metrikleri.

Faz 2'de amaç toplam mesafedir. Araç başına TL maliyet (yakıt, sürücü, sabit
gider, öncelik cezası) Faz 3'te bu modüle eklenir. Metrikler tek yerde
hesaplanır ki optimize çözüm ile baseline aynı terazide tartılsın.
"""

from __future__ import annotations

from dataclasses import dataclass
from collections.abc import Sequence
from itertools import pairwise

from .model import DEPOT, Problem, Route, Solution, Vehicle


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


# --- Faz 3: maliyet modeli (mimari §9) ---
#
# Raporlanan TL maliyet = yakıt + sürücü + araç sabit gideri.
# Optimize edilen amaç = TL maliyet + öncelik cezası.
#
# Öncelik cezası para değildir: zaman penceresi kapsam dışı olduğundan "ihlal"
# yoktur; bunun yerine yüksek öncelikli kargonun geç varması cezalandırılır:
#     ceza = katsayı × Σ öncelik_i × varış_saati_i
# Varış, depodan çıkıştan o durağa ulaşana kadar geçen süredir (servis hariç).


@dataclass(frozen=True)
class RouteCost:
    distance_m: float
    duration_s: float
    fuel_l: float
    fuel_try: float
    driver_try: float
    fixed_try: float
    penalty: float

    @property
    def money_try(self) -> float:
        return self.fuel_try + self.driver_try + self.fixed_try

    @property
    def objective(self) -> float:
        return self.money_try + self.penalty


def route_cost(problem: Problem, vehicle: Vehicle, stops: Sequence[int]) -> RouteCost:
    """Tek rotanın maliyet kırılımı. Boş rota depodan çıkmaz: her şey sıfır."""
    if not stops:
        return RouteCost(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    d, t, svc, prio = problem.dist_rows, problem.dur_rows, problem.service, problem.priority
    dist = clock = weighted = 0.0
    prev = DEPOT
    for i in stops:
        dist += d[prev][i]
        clock += t[prev][i]
        weighted += prio[i] * clock
        clock += svc[i]
        prev = i
    dist += d[prev][DEPOT]
    clock += t[prev][DEPOT]

    c = problem.costs
    if c is None:
        return RouteCost(dist, clock, 0.0, 0.0, 0.0, 0.0, 0.0)
    fuel_l = dist / 1000.0 * vehicle.fuel_l_per_100km / 100.0
    return RouteCost(
        distance_m=dist,
        duration_s=clock,
        fuel_l=fuel_l,
        fuel_try=fuel_l * c.fuel_price_per_l,
        driver_try=clock / 3600.0 * c.driver_cost_per_hour,
        fixed_try=vehicle.fixed_cost_per_day,
        penalty=c.priority_penalty * weighted / 3600.0,
    )


def route_objective(problem: Problem, vehicle: Vehicle, stops: Sequence[int]) -> float:
    """
    Algoritmaların küçülttüğü değer. Maliyet modeli yoksa mesafe (Faz 2).

    Boş rota 0'dır: kullanılmayan araç ne yakıt ne sabit gider yazar. Bu yüzden
    iki rotayı birleştirmek bir aracın sabit giderini kazandırır.
    """
    # route_cost ile aynı hesap, ara nesne üretmeden: yerel arama ve SA bunu
    # yüz binlerce kez çağırır. İkisinin eşitliği testte sabitlenmiştir.
    if not stops:
        return 0.0
    d, t, svc, prio = problem.dist_rows, problem.dur_rows, problem.service, problem.priority
    dist = clock = weighted = 0.0
    prev = DEPOT
    for i in stops:
        dist += d[prev][i]
        clock += t[prev][i]
        weighted += prio[i] * clock
        clock += svc[i]
        prev = i
    dist += d[prev][DEPOT]
    c = problem.costs
    if c is None:
        return dist
    clock += t[prev][DEPOT]
    return (dist * vehicle.fuel_l_per_100km / 100_000.0 * c.fuel_price_per_l
            + clock * c.driver_cost_per_hour / 3600.0
            + vehicle.fixed_cost_per_day
            + c.priority_penalty * weighted / 3600.0)


def solution_objective(problem: Problem, solution: Solution) -> float:
    return sum(route_objective(problem, r.vehicle, r.stops) for r in solution.routes)
