"""
Naive baseline — duraklar geliş sırasıyla, ilk sığan araca.

Listeyi yukarıdan aşağı okuyan bir dispatcher'ı temsil eder: her kargoyu, sırası
gelen ilk araca (araç listesi sırasıyla) yükü ve süresi sığdığı sürece koyar; araç
içinde ziyaret sırası da geliş sırasıdır. Bu sırayla hiçbir araca sığmayan kargoyu
dispatcher atmaz, uygun bir boşluğa koyar: en ucuz olurlu konuma eklenir. (Sıkı
çalışma gününde geliş sırası zikzak çizdirir; bu tamir olmadan baseline çöker ve
her koşuda hesaplandığı için asıl optimizasyonu da düşürürdü.) Her koşuda optimize çözümle birlikte hesaplanır;
tasarruf yüzdesi buna göre ölçülür. Bu yüzden "geliş sırası" anlamlı olmalıdır:
backend kargoları oluşturulma sırasıyla gönderir.
"""

from __future__ import annotations

from collections.abc import Sequence

from ..localsearch.state import RouteSet
from ..model import DEPOT, Problem, Route, Vehicle
from .insertion import insert_cheapest


def naive(problem: Problem, stops: Sequence[int], vehicle: Vehicle) -> Route:
    """Tek araç: geliş sırası olduğu gibi (Faz 2 imzası)."""
    del problem   # sıralama girdiden bağımsız; imza diğer kurucularla aynı kalsın
    return Route(vehicle=vehicle, stops=list(stops))


def naive_fleet(problem: Problem, stops: Sequence[int]) -> list[list[int]]:
    """Araç sırasıyla hizalı rotalar; yükü ve süresi sığan ilk araç."""
    routes: list[list[int]] = [[] for _ in problem.vehicles]
    load = [0.0] * len(problem.vehicles)
    clock = [0.0] * len(problem.vehicles)       # son durakta teslim bitişi (dönüş hariç)
    demand, t, svc = problem.demand, problem.dur_rows, problem.service
    limit = problem.max_duration_s
    leftovers: list[int] = []
    for i in stops:
        for k, v in enumerate(problem.vehicles):
            last = routes[k][-1] if routes[k] else DEPOT
            arrive_done = clock[k] + t[last][i] + svc[i]
            fits_time = limit is None or arrive_done + t[i][DEPOT] <= limit + 1e-6
            if load[k] + demand[i] <= v.capacity_kg + 1e-6 and fits_time:
                routes[k].append(i)
                load[k] += demand[i]
                clock[k] = arrive_done
                break
        else:
            leftovers.append(i)

    if not leftovers:
        return routes
    rs = RouteSet(problem, routes)
    for i in leftovers:
        insert_cheapest(rs, i)
    return rs.routes
