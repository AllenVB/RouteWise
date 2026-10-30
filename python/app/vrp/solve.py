"""
Çözücü — çok araç, kapasite, maliyet modeli (CVRP).

Akış:
  1. Depoyla gidiş-dönüş yolu olmayan durakları ayır (rapor edilir, rotaya girmez)
  2. Açıkça imkânsız girdiyi erken reddet: en büyük araca sığmayan kargo, filoyu
     aşan toplam yük — sessizce aşan rota yok
  3. İstenen algoritmayı ve naive baseline'ı AYNI durak kümesiyle koş
  4. İkisini de olurluluk kontrolünden geçir; ihlal programlama hatasıdır

Neden 1. adım yeter: matris gerçek yol grafında en kısa yollardan oluşur. Depoya
gidip dönülebilen her durak çifti arasında depo üzerinden geçen bir yol vardır,
dolayısıyla süzgeçten geçen duraklar birbirine de ulaşabilir.

Tohum: rastgelelik kullanan algoritma tohumsuz çağrılırsa tohum burada üretilir
ve sonuçla birlikte döner. Tohum olmadan sonuç tekrar üretilemez.
"""

from __future__ import annotations

import random
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import Enum

from ..pathfinding.base import Timer
from .construction.naive import naive_fleet
from .construction.nearest_neighbor import nearest_neighbor_fleet
from .construction.savings import savings
from .feasibility import violations
from .localsearch.state import RouteSet
from .metaheuristic.simulated_annealing import AnnealParams
from .model import DEPOT, InfeasibleError, Problem, Solution
from .routewise import routewise

UNREACHABLE = "UNREACHABLE"

__all__ = ["Algorithm", "InfeasibleError", "Outcome", "UNREACHABLE", "solve"]


class Algorithm(str, Enum):
    """Adlar `optimization_runs.algorithm` CHECK kısıtıyla aynıdır."""

    NAIVE = "NAIVE"
    GREEDY_NN = "GREEDY_NN"
    SAVINGS = "SAVINGS"
    ROUTEWISE = "ROUTEWISE"

    @property
    def seeded(self) -> bool:
        return self is Algorithm.ROUTEWISE


@dataclass
class Outcome:
    result: Solution
    baseline: Solution
    unassigned: dict[int, str]       # problem indeksi -> sebep
    seed: int | None                 # yalnızca tohumlu algoritmalarda


def solve(
    problem: Problem,
    algorithm: Algorithm,
    seed: int | None = None,
    anneal: AnnealParams = AnnealParams(),
    warm_start: Sequence[Sequence[int]] | None = None,
) -> Outcome:
    """`warm_start`: araç sırasıyla hizalı önceki rotalar (problem indeksleri)."""
    if not problem.vehicles:
        raise InfeasibleError("Aktif araç yok.")

    routable, unassigned = _split_reachable(problem)
    if not routable:
        raise InfeasibleError("Depodan hiçbir teslimat noktasına gidiş-dönüş yolu yok.")
    _precheck_capacity(problem, routable)

    if algorithm.seeded and seed is None:
        seed = random.SystemRandom().randrange(2**31)
    if not algorithm.seeded:
        seed = None

    builders: dict[Algorithm, Callable[[], list[list[int]]]] = {
        Algorithm.NAIVE: lambda: naive_fleet(problem, routable),
        Algorithm.GREEDY_NN: lambda: nearest_neighbor_fleet(problem, routable),
        Algorithm.SAVINGS: lambda: savings(problem, routable),
        Algorithm.ROUTEWISE: lambda: routewise(problem, routable, seed or 0, anneal, warm_start).routes,
    }

    baseline = _run(problem, Algorithm.NAIVE, builders[Algorithm.NAIVE])
    result = baseline if algorithm is Algorithm.NAIVE else _run(problem, algorithm, builders[algorithm])

    for sol in (result, baseline):
        if found := violations(problem, sol, routable):
            # Girdi süzüldükten sonra ihlal kalıyorsa hata algoritmadadır.
            raise RuntimeError(f"{sol.algorithm} olursuz çözüm üretti: {found}")

    return Outcome(result=result, baseline=baseline, unassigned=unassigned, seed=seed)


def _split_reachable(problem: Problem) -> tuple[list[int], dict[int, str]]:
    routable: list[int] = []
    unassigned: dict[int, str] = {}
    for i in range(1, problem.size + 1):
        if problem.reachable(DEPOT, i) and problem.reachable(i, DEPOT):
            routable.append(i)
        else:
            unassigned[i] = UNREACHABLE
    return routable, unassigned


def _precheck_capacity(problem: Problem, stops: list[int]) -> None:
    biggest = max(v.capacity_kg for v in problem.vehicles)
    for i in stops:
        if problem.demand[i] > biggest + 1e-6:
            s = problem.stop(i)
            raise InfeasibleError(
                f"Kargo {s.id} ({s.demand_kg:.2f} kg) en büyük aracın kapasitesini "
                f"({biggest:.2f} kg) aşıyor.")
    total = sum(problem.demand[i] for i in stops)
    fleet = sum(v.capacity_kg for v in problem.vehicles)
    if total > fleet + 1e-6:
        raise InfeasibleError(
            f"Toplam yük {total:.2f} kg, filonun toplam kapasitesi {fleet:.2f} kg. "
            "Bazı kargoların ertelenmesi gerekiyor.")


def _run(problem: Problem, algorithm: Algorithm, build: Callable[[], list[list[int]]]) -> Solution:
    with Timer() as t:
        routes = build()
    return RouteSet(problem, routes).to_solution(algorithm.value, t.ms)
