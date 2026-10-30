"""
Yerel arama ve SA'nın üzerinde çalıştığı değiştirilebilir çözüm durumu.

Rotalar problemdeki araç sırasıyla hizalıdır: `routes[k]` = `problem.vehicles[k]`
aracının durakları (boş olabilir). Her hamle "değişen rotalar" sözlüğüdür:
{araç indeksi: yeni durak listesi}. Hamle değerlendirmesi yalnızca değişen
rotaları yeniden hesaplar.

**Olurluluk tek yerde denetlenir: `delta()`.** Kapasiteyi aşan ya da yolu
olmayan bacak içeren hamle `None` döner ve hiçbir koşulda uygulanmaz. Operatörler
yalnızca aday üretir; kontrolü unutabilecek bir operatör yoktur.
"""

from __future__ import annotations

import math

from ..model import Problem, Route, Solution
from ..objective import route_objective

Changes = dict[int, list[int]]

_EPS_KG = 1e-6


class RouteSet:
    def __init__(self, problem: Problem, routes: list[list[int]]):
        if len(routes) != len(problem.vehicles):
            raise ValueError("her araç için bir rota (boş olabilir) gerekir")
        self.p = problem
        self.routes = [list(r) for r in routes]
        self.load = [self._load(r) for r in self.routes]
        self.cost = [route_objective(problem, v, r) for v, r in zip(problem.vehicles, self.routes, strict=True)]

    @classmethod
    def from_solution(cls, problem: Problem, solution: Solution) -> RouteSet:
        by_vehicle = {r.vehicle.id: r.stops for r in solution.routes}
        return cls(problem, [by_vehicle.get(v.id, []) for v in problem.vehicles])

    def _load(self, stops: list[int]) -> float:
        demand = self.p.demand
        return sum(demand[i] for i in stops)

    @property
    def total(self) -> float:
        return sum(self.cost)

    def delta(self, changes: Changes) -> float | None:
        """Amaçtaki değişim; olursuz hamle için None."""
        d = 0.0
        for k, stops in changes.items():
            vehicle = self.p.vehicles[k]
            if self._load(stops) > vehicle.capacity_kg + _EPS_KG:
                return None
            c = route_objective(self.p, vehicle, stops)
            if not math.isfinite(c):
                return None      # yolu olmayan bacak
            d += c - self.cost[k]
        return d

    def apply(self, changes: Changes) -> None:
        for k, stops in changes.items():
            self.routes[k] = list(stops)
            self.load[k] = self._load(stops)
            self.cost[k] = route_objective(self.p, self.p.vehicles[k], stops)

    def to_solution(self, algorithm: str, computation_time_ms: float = 0.0) -> Solution:
        return Solution(
            algorithm=algorithm,
            routes=[Route(v, list(r)) for v, r in zip(self.p.vehicles, self.routes, strict=True)],
            computation_time_ms=computation_time_ms,
        )
