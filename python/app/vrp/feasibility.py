"""
Olurluluk — sert kısıtlar.

Bu modül algoritmalardan önce yazıldı ve onlardan bağımsızdır. Bir çözümün
kapasiteyi aşıp aşmadığını algoritmanın kendisine sormak, sessizce geçersiz rota
üretmenin en kısa yoludur.

Kısıtlar:
  - kapasite: rota yükü araç kapasitesini aşamaz
  - sürülebilirlik: her bacakta yol olmalı (matriste inf yok)
  - kapsama: her durak tam olarak bir kez ziyaret edilir
  - filo: her araç en fazla bir rota sürer, yalnızca problemdeki araçlar
  - çalışma günü: rota süresi (yol + teslim + dönüş) `max_duration_s`'i aşamaz
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from itertools import pairwise

from .model import DEPOT, Problem, Route, Solution
from .objective import route_duration

# NUMERIC(8,2) ağırlıkların toplamında kayan nokta hatası kapasiteyi
# "aşmış" göstermesin.
_EPS_KG = 1e-6


def route_load(problem: Problem, route: Route) -> float:
    return sum(problem.stop(i).demand_kg for i in route.stops)


def capacity_ok(problem: Problem, route: Route) -> bool:
    return route_load(problem, route) <= route.vehicle.capacity_kg + _EPS_KG


# Saniye toplamlarında kayan nokta gürültüsü sınırı "aşmış" göstermesin.
_EPS_S = 1e-6


def duration_ok(problem: Problem, route: Route) -> bool:
    if problem.max_duration_s is None:
        return True
    return route_duration(problem, route.stops) <= problem.max_duration_s + _EPS_S


def drivable(problem: Problem, route: Route) -> bool:
    if not route.stops:
        return True
    path = [DEPOT, *route.stops, DEPOT]
    return all(problem.reachable(a, b) for a, b in pairwise(path))


def violations(problem: Problem, solution: Solution, expected: Iterable[int]) -> list[str]:
    """
    Çözümün kırdığı her sert kısıt için bir satır; boş liste = olurlu.

    `expected`: çözümde tam bir kez yer alması gereken problem indeksleri.
    """
    found: list[str] = []
    valid = range(1, problem.size + 1)

    for r in solution.routes:
        # Geçersiz indeksle yük ve bacak hesaplanamaz; bu rotada dur.
        if DEPOT in r.stops:
            found.append(f"arac {r.vehicle.id}: depo durak listesinde")
            continue
        if bad := [i for i in r.stops if i not in valid]:
            found.append(f"arac {r.vehicle.id}: gecersiz durak indeksi {bad}")
            continue
        if not capacity_ok(problem, r):
            found.append(f"arac {r.vehicle.id}: yuk {route_load(problem, r):.2f} kg "
                         f"> kapasite {r.vehicle.capacity_kg:.2f} kg")
        if not drivable(problem, r):
            found.append(f"arac {r.vehicle.id}: yolu olmayan bacak var")
        elif not duration_ok(problem, r):
            found.append(f"arac {r.vehicle.id}: sure {route_duration(problem, r.stops):.0f} s "
                         f"> calisma gunu {problem.max_duration_s:.0f} s")

    fleet = {v.id for v in problem.vehicles}
    used = Counter(r.vehicle.id for r in solution.routes)
    if twice := sorted(v for v, c in used.items() if c > 1):
        found.append(f"ayni arac birden fazla rotada: {twice}")
    if foreign := sorted(used.keys() - fleet):
        found.append(f"problemde olmayan arac: {foreign}")

    visits = Counter(i for r in solution.routes for i in r.stops if i in valid)
    want = set(expected)
    if dup := sorted(i for i, c in visits.items() if c > 1):
        found.append(f"birden fazla ziyaret: {dup}")
    if missing := sorted(want - visits.keys()):
        found.append(f"ziyaret edilmeyen: {missing}")
    if extra := sorted(visits.keys() - want):
        found.append(f"beklenmeyen durak: {extra}")
    return found
