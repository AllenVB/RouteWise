"""
Seviye 2 karşılaştırması — tek aracın tur sırası, tüm algoritmalar aynı girdiyle.

Algoritmalar: NAIVE (geliş sırası), GREEDY_NN, SAVINGS, ROUTEWISE ve durak sayısı
izin veriyorsa DFS_EXHAUSTIVE (kanıtlı optimum). Referans kesin optimumdur; yoksa
(n > 10) bulunan en iyi çözüm referans olur ve bu yanıtta açıkça söylenir —
"optimumdan sapma" ile "en iyiden sapma" aynı iddia değildir.

Tekrarlar: her algoritma `repeats` kez koşar, süre medyanı raporlanır.
Deterministik algoritmaların sonucu her koşuda aynıdır. ROUTEWISE (benzetimli
tavlama) her tekrarda farklı tohumla koşar; amacı medyan olan koşu raporlanır,
en iyi ve en kötü amaç da verilir — tek şanslı tohumla övünülmez.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field

from ..pathfinding.base import Timer
from .exhaustive import MAX_STOPS, exhaustive_tour
from .localsearch.state import RouteSet
from .metaheuristic.simulated_annealing import AnnealParams
from .model import InfeasibleError, Problem, Solution
from .objective import solution_objective
from .solve import Algorithm, solve

EXHAUSTIVE = "DFS_EXHAUSTIVE"
HEURISTICS = (Algorithm.NAIVE, Algorithm.GREEDY_NN, Algorithm.SAVINGS, Algorithm.ROUTEWISE)


@dataclass
class TourRun:
    algorithm: str
    solution: Solution | None          # None: bu algoritma olurlu tur kuramadı
    error: str | None
    median_ms: float
    seed: int | None = None
    objective_min: float | None = None
    objective_max: float | None = None
    explored: int | None = None
    gap_pct: float | None = None


@dataclass
class TourComparison:
    runs: list[TourRun]
    unassigned: dict[int, str]
    reference: str | None              # sapmanın ölçüldüğü algoritma
    reference_is_optimal: bool
    exhaustive_skipped: str | None = None
    seeds: list[int] = field(default_factory=list)


def compare_tours(problem: Problem, repeats: int = 5, seed: int = 0,
                  anneal: AnnealParams = AnnealParams()) -> TourComparison:
    """InfeasibleError: girdinin kendisi imkânsız (kapasite, hiç ulaşılabilir durak yok)."""
    if len(problem.vehicles) != 1:
        raise ValueError("tur karşılaştırması tek araç içindir")
    if repeats < 1:
        raise ValueError("repeats en az 1 olmalı")

    runs: list[TourRun] = []
    unassigned: dict[int, str] | None = None
    seeds = [seed + k for k in range(repeats)]
    for alg in HEURISTICS:
        outcomes = []
        error = None
        for k in range(repeats):
            try:
                out = solve(problem, alg, seed=seeds[k] if alg.seeded else None, anneal=anneal)
            except InfeasibleError as e:
                error = str(e)
                break
            outcomes.append(out)
            unassigned = out.unassigned
        if error is not None or not outcomes:
            runs.append(TourRun(alg.value, None, error, 0.0))
            continue
        objectives = [solution_objective(problem, o.result) for o in outcomes]
        pick = _median_index(objectives)
        runs.append(TourRun(
            alg.value, outcomes[pick].result, None,
            statistics.median(o.result.computation_time_ms for o in outcomes),
            seed=outcomes[pick].seed,
            objective_min=min(objectives), objective_max=max(objectives),
        ))

    if unassigned is None:
        # Hiçbiri kuramadıysa sebep girdinin kendisidir (kapasite, ulaşılamazlık, çalışma günü).
        raise InfeasibleError(next(r.error for r in runs if r.error) or "Bu girdiyle tur kurulamıyor.")
    routable = [i for i in range(1, problem.size + 1) if i not in unassigned]

    skipped = None
    if len(routable) > MAX_STOPS:
        skipped = f"{len(routable)} durak; kesin arama en fazla {MAX_STOPS} durakta koşar."
    else:
        runs.append(_exhaustive(problem, routable, runs, repeats))

    feasible = [r for r in runs if r.solution is not None]
    exact = next((r for r in feasible if r.algorithm == EXHAUSTIVE), None)
    ref = exact or min(feasible, key=lambda r: solution_objective(problem, r.solution), default=None)
    if ref is not None:
        ref_obj = solution_objective(problem, ref.solution)
        for r in feasible:
            obj = solution_objective(problem, r.solution)
            r.gap_pct = 0.0 if ref_obj == 0 else (obj - ref_obj) / ref_obj * 100.0
    return TourComparison(runs, unassigned, None if ref is None else ref.algorithm, exact is not None,
                          skipped, seeds)


def _exhaustive(problem: Problem, routable: list[int], runs: list[TourRun], repeats: int) -> TourRun:
    vehicle = problem.vehicles[0]
    # En iyi sezgisel başlangıç üst sınırıdır; süre ölçümüne dahil (algoritmanın kendisi değil,
    # ama adil olsun diye kesmeyi güçlendiren ön bilgi her tekrarda yeniden verilir).
    heuristics = [r for r in runs if r.solution is not None]
    incumbent = None
    if heuristics:
        best = min(heuristics, key=lambda r: solution_objective(problem, r.solution))
        incumbent = [i for route in best.solution.routes for i in route.stops]
    times = []
    tour = None
    for _ in range(repeats):
        try:
            with Timer() as timer:
                tour = exhaustive_tour(problem, vehicle, routable, incumbent)
        except InfeasibleError as e:
            return TourRun(EXHAUSTIVE, None, str(e), 0.0)
        times.append(timer.ms)
    assert tour is not None
    sol = RouteSet(problem, [tour.stops]).to_solution(EXHAUSTIVE, statistics.median(times))
    return TourRun(EXHAUSTIVE, sol, None, statistics.median(times), explored=tour.explored)


def _median_index(values: list[float]) -> int:
    """Medyan değerli koşunun indeksi (çift sayıda tekrarda alttaki)."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    return order[(len(order) - 1) // 2]

