"""
En ucuz ekleme — bir durağı, amacı en az artıran olurlu konuma yerleştirir.

Kurucuların ortak tamiri (bir rotaya sığmayan durak) ve sıcak başlangıcın
temelidir: yeni gelen kargo mevcut rotaları dağıtmadan en ucuz yere girer.
"""

from __future__ import annotations

from ..localsearch.state import RouteSet
from ..model import InfeasibleError


def insert_cheapest(rs: RouteSet, stop: int) -> None:
    best: tuple[float, int, int] | None = None
    for k, route in enumerate(rs.routes):
        for j in range(len(route) + 1):
            d = rs.delta({k: route[:j] + [stop] + route[j:]})
            if d is not None and (best is None or d < best[0]):
                best = (d, k, j)
    if best is None:
        s = rs.p.stop(stop)
        raise InfeasibleError(
            f"Kargo {s.id} ({s.demand_kg:.2f} kg) hiçbir araca sığmıyor; filonun kalan kapasitesi yetersiz.")
    _, k, j = best
    route = rs.routes[k]
    rs.apply({k: route[:j] + [stop] + route[j:]})
