"""
Relocate — bir durağı başka bir konuma taşı (aynı rota içinde ya da başka rotaya).

Boş bir araca taşımak da adaydır: sabit gider artar ama yük dağılabilir.
Bir rotanın son durağını taşımak aracı boşa çıkarır ve sabit giderini kazandırır.
"""

from __future__ import annotations

import random
from collections.abc import Iterator

from .state import Changes, RouteSet


def move(rs: RouteSet, k: int, i: int, m: int, j: int) -> Changes | None:
    """k rotasının i. durağını m rotasının j. konumuna (çıkarıldıktan sonraki indeks)."""
    src = rs.routes[k]
    stop = src[i]
    if k == m:
        rest = src[:i] + src[i + 1:]
        new = rest[:j] + [stop] + rest[j:]
        return None if new == src else {k: new}
    dst = rs.routes[m]
    return {k: src[:i] + src[i + 1:], m: dst[:j] + [stop] + dst[j:]}


def neighbors(rs: RouteSet) -> Iterator[Changes]:
    routes = rs.routes
    for k, src in enumerate(routes):
        for i in range(len(src)):
            for m, dst in enumerate(routes):
                limit = len(src) - 1 if m == k else len(dst)
                for j in range(limit + 1):
                    ch = move(rs, k, i, m, j)
                    if ch is not None:
                        yield ch


def random_neighbor(rs: RouteSet, rng: random.Random) -> Changes | None:
    sources = [k for k, r in enumerate(rs.routes) if r]
    if not sources:
        return None
    k = rng.choice(sources)
    i = rng.randrange(len(rs.routes[k]))
    m = rng.randrange(len(rs.routes))
    limit = len(rs.routes[k]) - 1 if m == k else len(rs.routes[m])
    return move(rs, k, i, m, rng.randint(0, limit))
