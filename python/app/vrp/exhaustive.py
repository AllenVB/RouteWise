"""
DFS_EXHAUSTIVE — tek araç için kesin optimum tur (Seviye 2 referansı).

Tüm ziyaret sıraları derinlik öncelikli gezilir; dal-sınır (branch and bound) ile
iyi olamayacağı kesin dallar kesilir. Sonuç, kullanılan amaç fonksiyonuna göre
**kanıtlı optimumdur**: sezgisellerin "optimumun %X içinde" iddiası buna göre ölçülür.

Neden kesme güvenli: amaç (yakıt + sürücü + sabit gider + öncelik cezası) bir
önekten sonra yalnızca büyür — her terim negatif olmayan katkıların toplamıdır.
Önekin maliyeti artı henüz ziyaret edilmemiş durakların en az cezası (en erken
şimdiki saatte varırlar) bir alt sınırdır; bu sınır en iyi bilinen çözümü aşan dal
optimum içeremez. Çalışma günü sınırı da önekte kesilir: saat geri gitmez.

Durak sayısı sınırı (MAX_STOPS = 10): 10! = 3,6 milyon sıra. Sınırla birlikte
saniyenin altında biter; 11'den sonrası katlanarak büyür ve karşılaştırma ucunu
kilitler.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from .model import DEPOT, InfeasibleError, Problem, Vehicle
from .objective import route_objective

MAX_STOPS = 10
EPS = 1e-9


@dataclass
class ExactTour:
    stops: list[int]          # problem indeksleri, ziyaret sırasıyla
    objective: float
    explored: int             # açılan arama düğümü sayısı (kesmenin etkisi)


def exhaustive_tour(problem: Problem, vehicle: Vehicle, stops: Sequence[int],
                    incumbent: Sequence[int] | None = None) -> ExactTour:
    """
    incumbent: bilinen olurlu bir sıra (ör. bir sezgiselin sonucu) — başlangıç üst
    sınırıdır, kesmeyi baştan güçlendirir. Optimum ona eşitse o sıra döner.

    InfeasibleError: hiçbir sıra çalışma gününe sığmıyor ya da kargolar araca sığmıyor.
    ValueError: MAX_STOPS'tan fazla durak.
    """
    stops = list(stops)
    if len(stops) > MAX_STOPS:
        raise ValueError(f"kesin arama en fazla {MAX_STOPS} durak içindir, {len(stops)} geldi")
    if sum(problem.demand[i] for i in stops) > vehicle.capacity_kg + 1e-6:
        raise InfeasibleError("Kargolar tek araca sığmıyor; kesin tur hesaplanamaz.")
    if not stops:
        return ExactTour([], 0.0, 0)

    d, t, svc, prio = problem.dist_rows, problem.dur_rows, problem.service, problem.priority
    limit = problem.max_duration_s
    c = problem.costs
    if c is None:
        per_m, per_s, fixed, per_wait = 1.0, 0.0, 0.0, 0.0
    else:
        per_m = vehicle.fuel_l_per_100km / 100_000.0 * c.fuel_price_per_l
        per_s = c.driver_cost_per_hour / 3600.0
        fixed = vehicle.fixed_cost_per_day
        per_wait = c.priority_penalty / 3600.0

    best_cost = float("inf")
    best_order: list[int] | None = None
    if incumbent is not None and sorted(incumbent) == sorted(stops) and _fits(problem, incumbent):
        best_cost = route_objective(problem, vehicle, incumbent)
        best_order = list(incumbent)

    order: list[int] = []
    left = set(stops)
    explored = 0

    def dfs(prev: int, dist: float, clock: float, weighted: float, prio_left: float) -> None:
        nonlocal best_cost, best_order, explored
        explored += 1
        if not left:
            back_clock = clock + t[prev][DEPOT]
            if limit is not None and back_clock > limit + 1e-6:
                return
            total = (dist + d[prev][DEPOT]) * per_m + fixed + weighted * per_wait
            # Maliyet modeli yoksa amaç yalnızca mesafedir (route_objective ile aynı).
            total += back_clock * per_s
            if total < best_cost - EPS or best_order is None:
                best_cost = total
                best_order = list(order)
            return
        for j in sorted(left):
            arrive = clock + t[prev][j]
            done = arrive + svc[j]
            if limit is not None and done > limit + 1e-6:
                continue
            nd = dist + d[prev][j]
            nw = weighted + prio[j] * arrive
            np_left = prio_left - prio[j]
            bound = nd * per_m + done * per_s + fixed + (nw + np_left * done) * per_wait
            if bound > best_cost + EPS:
                continue
            left.remove(j)
            order.append(j)
            dfs(j, nd, done, nw, np_left)
            order.pop()
            left.add(j)

    dfs(DEPOT, 0.0, 0.0, 0.0, float(sum(prio[i] for i in stops)))
    if best_order is None:
        raise InfeasibleError("Bu kargoların hiçbir sırası çalışma gününe sığmıyor.")
    return ExactTour(best_order, route_objective(problem, vehicle, best_order), explored)


def _fits(problem: Problem, stops: Sequence[int]) -> bool:
    if problem.max_duration_s is None:
        return True
    t, svc = problem.dur_rows, problem.service
    clock = 0.0
    prev = DEPOT
    for i in stops:
        clock += t[prev][i] + svc[i]
        prev = i
    return clock + t[prev][DEPOT] <= problem.max_duration_s + 1e-6
