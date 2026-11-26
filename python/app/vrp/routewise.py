"""
RouteWise — projenin kendi algoritması (mimari §8).

    Clarke-Wright savings  →  yerel arama  →  simulated annealing  →  yerel arama
       (başlangıç)          (2-opt, relocate,     (tohumlanmış)          (cilalama)
                             exchange, Or-opt)

Sıcak başlangıç (önceki uygulanan koşu verilirse) bilerek **az** iyileştirir:
önceki duraklar kendi araçlarında ve sıralarında kalır, yeni duraklar en ucuz
olurlu konuma eklenir ve yerel arama yalnızca yeni durak alan rotalarda, rota
içinde çalışır. SA çalışmaz. Kuryenin elindeki rotanın kararlılığı, birkaç
yüz metrelik kazançtan değerlidir; tam yeniden optimizasyon isteyen sıcak
başlangıç göndermez. (İlk sürüm SA'yı düşük sıcaklıkla çalıştırıyordu: tek kargo
eklenince önceki rotanın yalnızca ~%45'i korunuyordu.)

Her aşama aynı `RouteSet` üzerinde çalışır; olurluluk tek yerde (`delta`)
denetlendiği için hiçbir aşama olursuz çözüme geçemez.
"""

from __future__ import annotations

import random
from collections.abc import Iterator, Sequence

from .construction.insertion import insert_cheapest
from .construction.savings import savings
from .localsearch import or_opt, relocate, two_opt
from .localsearch.search import Neighborhood, local_search
from .localsearch.state import Changes, RouteSet
from .metaheuristic.simulated_annealing import AnnealParams, anneal
from .model import Problem


def routewise(
    problem: Problem,
    stops: Sequence[int],
    seed: int,
    params: AnnealParams = AnnealParams(),
    warm_start: Sequence[Sequence[int]] | None = None,
) -> RouteSet:
    if warm_start is not None:
        rs, touched = _from_warm_start(problem, stops, warm_start)
        local_search(rs, _within(touched))
        return rs

    rs = RouteSet(problem, savings(problem, stops))
    local_search(rs)
    anneal(rs, random.Random(seed), params)
    local_search(rs)
    return rs


def _within(touched: set[int]) -> tuple[Neighborhood, ...]:
    """Yalnızca dokunulan rotaların içinde kalan hamleler."""

    def restrict(op: Neighborhood) -> Neighborhood:
        def neighbors(rs: RouteSet) -> Iterator[Changes]:
            return (c for c in op(rs) if len(c) == 1 and next(iter(c)) in touched)
        return neighbors

    return tuple(restrict(op) for op in (two_opt.neighbors, relocate.neighbors, or_opt.neighbors))


def _from_warm_start(problem: Problem, stops: Sequence[int],
                     previous: Sequence[Sequence[int]]) -> tuple[RouteSet, set[int]]:
    """
    Önceki rotalardan devam: artık planlanmayan duraklar düşer, yeniler eklenir.

    Önceki çözüm bugün olursuz olabilir (ör. bir kargonun ağırlığı arttı): olursuz
    kalan rota boşaltılır ve durakları en ucuz konuma yeniden eklenir.

    Döner: çözüm ve değişen rotaların araç indeksleri.
    """
    wanted = set(stops)
    seen: set[int] = set()
    kept: list[list[int]] = []
    for r in previous:
        route: list[int] = []
        for i in r:
            # Bozuk girdide aynı durak iki rotada olabilir: ilk görülen kalır.
            if i in wanted and i not in seen:
                seen.add(i)
                route.append(i)
        kept.append(route)

    empty = RouteSet(problem, [[] for _ in problem.vehicles])
    routes: list[list[int]] = []
    displaced: list[int] = []
    touched: set[int] = set()
    for k, r in enumerate(kept):
        if r and empty.delta({k: r}) is None:
            displaced.extend(r)
            routes.append([])
            touched.add(k)
        else:
            routes.append(r)

    rs = RouteSet(problem, routes)
    for i in [*displaced, *(i for i in stops if i not in seen)]:
        before = [list(r) for r in rs.routes]
        insert_cheapest(rs, i)
        touched |= {k for k, r in enumerate(rs.routes) if r != before[k]}
    return rs, touched
