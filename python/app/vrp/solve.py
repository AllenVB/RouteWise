"""
Faz 2 çözücü — tek araç, tur sırası (TSP).

Akış:
  1. Depoyla gidiş-dönüş yolu olmayan durakları ayır (rapor edilir, rotaya girmez)
  2. Kalan yük araç kapasitesini aşıyorsa açıkça reddet — sessizce aşan rota yok
  3. İstenen algoritmayı ve naive baseline'ı AYNI durak kümesiyle koş
  4. İkisini de olurluluk kontrolünden geçir; ihlal programlama hatasıdır

Neden 1. adım yeter: matris gerçek yol grafında en kısa yollardan oluşur. Depoya
gidip dönülebilen her durak çifti arasında depo üzerinden geçen bir yol vardır,
dolayısıyla süzgeçten geçen duraklar birbirine de ulaşabilir.

Çok araç ve kapasiteye göre bölme Faz 3'ün işidir (CVRP).
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import Enum

from ..pathfinding.base import Timer
from .construction.naive import naive
from .construction.nearest_neighbor import StuckError, nearest_neighbor
from .feasibility import capacity_ok, route_load, violations
from .model import DEPOT, Problem, Route, Solution, Vehicle

UNREACHABLE = "UNREACHABLE"


class Algorithm(str, Enum):
    """Adlar `optimization_runs.algorithm` CHECK kısıtıyla aynıdır."""

    NAIVE = "NAIVE"
    GREEDY_NN = "GREEDY_NN"


Builder = Callable[[Problem, Sequence[int], Vehicle], Route]

_BUILDERS: dict[Algorithm, Builder] = {
    Algorithm.NAIVE: naive,
    Algorithm.GREEDY_NN: nearest_neighbor,
}


class InfeasibleError(Exception):
    """Sert kısıtlar bu girdiyle karşılanamıyor — istemci akışı değiştirmeli."""


@dataclass
class Outcome:
    result: Solution
    baseline: Solution
    unassigned: dict[int, str]       # problem indeksi -> sebep


def solve(problem: Problem, algorithm: Algorithm) -> Outcome:
    if len(problem.vehicles) != 1:
        raise ValueError("Faz 2 çözücüsü tek araç destekler")
    vehicle = problem.vehicles[0]

    routable, unassigned = _split_reachable(problem)
    if not routable:
        raise InfeasibleError("Depodan hiçbir teslimat noktasına gidiş-dönüş yolu yok.")

    everything = Route(vehicle=vehicle, stops=routable)
    if not capacity_ok(problem, everything):
        raise InfeasibleError(
            f"Toplam yük {route_load(problem, everything):.2f} kg, "
            f"araç kapasitesi {vehicle.capacity_kg:.2f} kg. "
            "Tek araçlı planlamada yük kapasiteyi aşamaz.")

    baseline = _run(problem, Algorithm.NAIVE, routable, vehicle)
    result = baseline if algorithm is Algorithm.NAIVE else _run(problem, algorithm, routable, vehicle)

    for sol in (result, baseline):
        if found := violations(problem, sol, routable):
            # Girdi süzüldükten sonra ihlal kalıyorsa hata algoritmadadır.
            raise RuntimeError(f"{sol.algorithm} olursuz çözüm üretti: {found}")

    return Outcome(result=result, baseline=baseline, unassigned=unassigned)


def _split_reachable(problem: Problem) -> tuple[list[int], dict[int, str]]:
    routable: list[int] = []
    unassigned: dict[int, str] = {}
    for i in range(1, problem.size + 1):
        if problem.reachable(DEPOT, i) and problem.reachable(i, DEPOT):
            routable.append(i)
        else:
            unassigned[i] = UNREACHABLE
    return routable, unassigned


def _run(problem: Problem, algorithm: Algorithm, stops: list[int], vehicle: Vehicle) -> Solution:
    with Timer() as t:
        try:
            route = _BUILDERS[algorithm](problem, stops, vehicle)
        except StuckError as e:
            # En kısa yol matrisinde süzgeçten sonra olamaz; elle kurulmuş
            # tutarsız bir matrisin işaretidir.
            raise InfeasibleError(f"Rota kurulamadı: {e}") from e
    return Solution(algorithm=algorithm.value, routes=[route], computation_time_ms=t.ms)
