"""
Exchange — iki durağın yerini değiştir (aynı rota içinde ya da iki rota arasında).

Rotalar arası takas, relocate'in kapasite yüzünden yapamadığı hamleyi yapar:
dolu bir araçtan durak çıkarmak için karşılığında daha hafif bir durak alır.
"""

from __future__ import annotations

import random
from collections.abc import Iterator

from .state import Changes, RouteSet


def swap(rs: RouteSet, k: int, i: int, m: int, j: int) -> Changes:
    a, b = rs.routes[k], rs.routes[m]
    if k == m:
        new = list(a)
        new[i], new[j] = new[j], new[i]
        return {k: new}
    na, nb = list(a), list(b)
    na[i], nb[j] = b[j], a[i]
    return {k: na, m: nb}


def neighbors(rs: RouteSet) -> Iterator[Changes]:
    routes = rs.routes
    for k, a in enumerate(routes):
        for i in range(len(a)):
            for j in range(i + 1, len(a)):
                yield swap(rs, k, i, k, j)
            for m in range(k + 1, len(routes)):
                for j in range(len(routes[m])):
                    yield swap(rs, k, i, m, j)


def random_neighbor(rs: RouteSet, rng: random.Random) -> Changes | None:
    filled = [k for k, r in enumerate(rs.routes) if r]
    if not filled:
        return None
    k, m = rng.choice(filled), rng.choice(filled)
    i, j = rng.randrange(len(rs.routes[k])), rng.randrange(len(rs.routes[m]))
    if k == m and i == j:
        return None
    return swap(rs, k, i, m, j)
