"""
Naive baseline — duraklar geliş sırasıyla, ilk sığan araca.

Listeyi yukarıdan aşağı okuyan bir dispatcher'ı temsil eder: her kargoyu, sırası
gelen ilk araca (araç listesi sırasıyla) yükü sığdığı sürece koyar; araç içinde
ziyaret sırası da geliş sırasıdır. Her koşuda optimize çözümle birlikte hesaplanır;
tasarruf yüzdesi buna göre ölçülür. Bu yüzden "geliş sırası" anlamlı olmalıdır:
backend kargoları oluşturulma sırasıyla gönderir.
"""

from __future__ import annotations

from collections.abc import Sequence

from ..model import InfeasibleError, Problem, Route, Vehicle


def naive(problem: Problem, stops: Sequence[int], vehicle: Vehicle) -> Route:
    """Tek araç: geliş sırası olduğu gibi (Faz 2 imzası)."""
    del problem   # sıralama girdiden bağımsız; imza diğer kurucularla aynı kalsın
    return Route(vehicle=vehicle, stops=list(stops))


def naive_fleet(problem: Problem, stops: Sequence[int]) -> list[list[int]]:
    """Araç sırasıyla hizalı rotalar; ilk sığan araç."""
    routes: list[list[int]] = [[] for _ in problem.vehicles]
    load = [0.0] * len(problem.vehicles)
    demand = problem.demand
    for i in stops:
        for k, v in enumerate(problem.vehicles):
            if load[k] + demand[i] <= v.capacity_kg + 1e-6:
                routes[k].append(i)
                load[k] += demand[i]
                break
        else:
            s = problem.stop(i)
            raise InfeasibleError(
                f"Geliş sırasıyla yüklemede kargo {s.id} ({s.demand_kg:.2f} kg) hiçbir araca sığmadı.")
    return routes
