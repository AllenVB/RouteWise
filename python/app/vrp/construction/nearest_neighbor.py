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

from ..model import DEPOT, Problem, Route, Vehicle


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
