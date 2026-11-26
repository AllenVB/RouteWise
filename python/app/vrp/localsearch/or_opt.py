"""
Or-opt — ardışık 2–3 duraklık bir segmenti başka bir konuma taşı.

Aynı sokaktaki komşu teslimatlar birlikte hareket eder; relocate bunları tek
tek taşırken ara adımda maliyet artar ve yerel arama oraya hiç gidemez.

Tam tarama granülerdir: segment yalnızca başının önüne ya da sonunun arkasına
yakın komşusu gelecek konumlara denenir.
"""

from __future__ import annotations

import random
from collections.abc import Iterator

from ..model import DEPOT
from .state import Changes, RouteSet

SEGMENT_LENGTHS = (2, 3)


def move(rs: RouteSet, k: int, i: int, length: int, m: int, j: int) -> Changes | None:
    src = rs.routes[k]
    seg = src[i:i + length]
    rest = src[:i] + src[i + length:]
    if k == m:
        new = rest[:j] + seg + rest[j:]
        return None if new == src else {k: new}
    dst = rs.routes[m]
    return {k: rest, m: dst[:j] + seg + dst[j:]}


def neighbors(rs: RouteSet) -> Iterator[Changes]:
    routes = rs.routes
    near = rs.p.near
    for length in SEGMENT_LENGTHS:
        for k, src in enumerate(routes):
            for i in range(len(src) - length + 1):
                head, tail = near[src[i]], near[src[i + length - 1]]
                for m, dst in enumerate(routes):
                    seq = src[:i] + src[i + length:] if m == k else dst
                    for j in range(len(seq) + 1):
                        prev = seq[j - 1] if j > 0 else DEPOT
                        nxt = seq[j] if j < len(seq) else DEPOT
                        if prev not in head and nxt not in tail:
                            continue
                        ch = move(rs, k, i, length, m, j)
                        if ch is not None:
                            yield ch


def random_neighbor(rs: RouteSet, rng: random.Random) -> Changes | None:
    length = rng.choice(SEGMENT_LENGTHS)
    sources = [k for k, r in enumerate(rs.routes) if len(r) >= length]
    if not sources:
        return None
    k = rng.choice(sources)
    i = rng.randrange(len(rs.routes[k]) - length + 1)
    m = rng.randrange(len(rs.routes))
    limit = len(rs.routes[k]) - length if m == k else len(rs.routes[m])
    return move(rs, k, i, length, m, rng.randint(0, limit))
