"""
2-opt — rota içi segment ters çevirme.

Matris asimetriktir (tek yönlü sokaklar): ters çevrilen segment ters yönde
sürülür ve mesafesi değişebilir. Bu yüzden klasik dört-kenar delta formülü
kullanılmaz; değişen rota baştan değerlendirilir (bkz. RouteSet.delta).
"""

from __future__ import annotations

import random
from collections.abc import Iterator

from .state import Changes, RouteSet


def neighbors(rs: RouteSet) -> Iterator[Changes]:
    for k, r in enumerate(rs.routes):
        n = len(r)
        for i in range(n - 1):
            for j in range(i + 1, n):
                yield {k: r[:i] + r[i:j + 1][::-1] + r[j + 1:]}


def random_neighbor(rs: RouteSet, rng: random.Random) -> Changes | None:
    candidates = [k for k, r in enumerate(rs.routes) if len(r) >= 2]
    if not candidates:
        return None
    k = rng.choice(candidates)
    r = rs.routes[k]
    i, j = sorted(rng.sample(range(len(r)), 2))
    return {k: r[:i] + r[i:j + 1][::-1] + r[j + 1:]}
