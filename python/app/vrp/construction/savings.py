"""
Clarke-Wright savings — RouteWise'ın başlangıç çözümü (paralel sürüm).

Her durak kendi rotasıyla başlar. i ile biten rota ile j ile başlayan rota
birleştirilirse kazanç:
    s(i, j) = d(i, 0) + d(0, j) - d(i, j)
Kazançlar büyükten küçüğe denenir; yalnızca uç uca eklenebilen ve en büyük
araca sığan birleşmeler yapılır.

Projeye özgü iki uyarlama:
  - **Asimetrik matris** (tek yönlü sokaklar): s(i, j) ≠ s(j, i). Birleşme yalnızca
    "i ile biten" + "j ile başlayan" yönünde yapılır; ters çevirme yok, çünkü ters
    yönde sürmek farklı mesafedir.
  - **Farklı kapasiteli filo**: rotalar en büyük kapasiteye göre kurulur, sonra
    büyükten küçüğe araçlara atanır. Araca düşmeyen rotanın durakları en ucuz
    olurlu konuma eklenir; o da olmazsa açık bir hata verilir.
"""

from __future__ import annotations

import math
from collections.abc import Sequence

from ..localsearch.state import RouteSet
from ..model import DEPOT, Problem
from .insertion import insert_cheapest


def savings(problem: Problem, stops: Sequence[int]) -> list[list[int]]:
    d, demand = problem.dist_rows, problem.demand
    cap_max = max(v.capacity_kg for v in problem.vehicles) + 1e-6

    route_of: dict[int, int] = {i: i for i in stops}       # durak -> rota kimliği
    routes: dict[int, list[int]] = {i: [i] for i in stops}
    load: dict[int, float] = {i: demand[i] for i in stops}

    pairs = [
        (d[i][DEPOT] + d[DEPOT][j] - d[i][j], i, j)
        for i in stops for j in stops
        if i != j and math.isfinite(d[i][j])
    ]
    # Eşit kazançta (i, j) sırası kararı belirler: aynı girdi aynı rota.
    pairs.sort(key=lambda t: (-t[0], t[1], t[2]))

    for s, i, j in pairs:
        if s <= 0:
            break
        ri, rj = route_of[i], route_of[j]
        if ri == rj or routes[ri][-1] != i or routes[rj][0] != j:
            continue
        if load[ri] + load[rj] > cap_max:
            continue
        routes[ri].extend(routes[rj])
        load[ri] += load[rj]
        for x in routes[rj]:
            route_of[x] = ri
        del routes[rj], load[rj]

    return assign(problem, list(routes.values()))


def assign(problem: Problem, built: list[list[int]]) -> list[list[int]]:
    """Kurulan rotaları araçlara yerleştir: en ağır rota en büyük boş araca."""
    vehicles = problem.vehicles
    by_capacity = sorted(range(len(vehicles)), key=lambda k: -vehicles[k].capacity_kg)
    demand = problem.demand
    aligned: list[list[int]] = [[] for _ in vehicles]
    leftovers: list[int] = []

    for route in sorted(built, key=lambda r: (-sum(demand[i] for i in r), r[0])):
        load = sum(demand[i] for i in route)
        slot = next((k for k in by_capacity
                     if not aligned[k] and load <= vehicles[k].capacity_kg + 1e-6), None)
        if slot is None:
            leftovers.extend(route)
        else:
            aligned[slot] = list(route)

    rs = RouteSet(problem, aligned)
    for i in sorted(leftovers, key=lambda x: (-demand[x], x)):   # ağırlar önce yerleşsin
        insert_cheapest(rs, i)
    return rs.routes
