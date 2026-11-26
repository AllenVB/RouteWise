"""
Nearest neighbor — açgözlü tur kurucu.

Depodan başlar, her adımda bulunduğu yerden en yakın ziyaret edilmemiş durağa
gider. Matris asimetriktir (tek yönlü yollar): "en yakın" daima bulunulan
noktadan adaya olan mesafedir, tersi değil.

Eşitlikte aday listesinde önce geleni seçer; aynı girdi her zaman aynı turu
verir.
"""

from __future__ import annotations

import math
from collections.abc import Sequence

from ..localsearch.state import RouteSet
from ..model import DEPOT, Problem, Route, Vehicle
from .insertion import insert_cheapest


class StuckError(Exception):
    """Bulunulan noktadan kalan hiçbir durağa yol yok."""


def nearest_neighbor(problem: Problem, stops: Sequence[int], vehicle: Vehicle) -> Route:
    remaining = list(stops)
    order: list[int] = []
    current = DEPOT
    dist = problem.distance

    while remaining:
        # min() eşitlikte ilk elemanı döndürür -> aday sırasıyla kararlı.
        k = min(range(len(remaining)), key=lambda j: dist[current, remaining[j]])
        nxt = remaining[k]
        if not math.isfinite(dist[current, nxt]):
            raise StuckError(
                f"durak {current} noktasından kalan {len(remaining)} durağa yol yok")
        order.append(remaining.pop(k))
        current = nxt

    return Route(vehicle=vehicle, stops=order)


def nearest_neighbor_fleet(problem: Problem, stops: Sequence[int]) -> list[list[int]]:
    """
    Filo için açgözlü kurucu: araçlar büyükten küçüğe doldurulur; her araç depodan
    başlayıp yüküne sığan en yakın durağa gider, sığan kalmayınca sıradaki araç.
    Hiçbir araca bu şekilde girmeyen durak en ucuz olurlu konuma eklenir.
    """
    remaining = list(stops)
    routes: list[list[int]] = [[] for _ in problem.vehicles]
    dist, demand, t, svc = problem.dist_rows, problem.demand, problem.dur_rows, problem.service
    limit = math.inf if problem.max_duration_s is None else problem.max_duration_s + 1e-6
    order = sorted(range(len(problem.vehicles)), key=lambda k: -problem.vehicles[k].capacity_kg)

    for k in order:
        cap = problem.vehicles[k].capacity_kg + 1e-6
        load, clock, current = 0.0, 0.0, DEPOT

        def fits(x: int) -> bool:
            # Kapasite + o durağa gidip teslim edip depoya dönebilecek zaman
            return (load + demand[x] <= cap and math.isfinite(dist[current][x])
                    and clock + t[current][x] + svc[x] + t[x][DEPOT] <= limit)

        while True:
            cands = [j for j in range(len(remaining)) if fits(remaining[j])]
            if not cands:
                break
            j = min(cands, key=lambda x: dist[current][remaining[x]])
            nxt = remaining.pop(j)
            routes[k].append(nxt)
            load += demand[nxt]
            clock += t[current][nxt] + svc[nxt]
            current = nxt

    rs = RouteSet(problem, routes)
    for i in remaining:
        insert_cheapest(rs, i)
    return rs.routes
