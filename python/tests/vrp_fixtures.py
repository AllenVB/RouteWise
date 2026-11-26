"""
VRP testleri için küçük problem kurucuları.

Gerçek grafa ve matris servisine bağımlı değildir: problem doğrudan problem
indeksli matristen kurulur (0 depo, 1..n duraklar).
"""

from __future__ import annotations

import math

import numpy as np

from app.vrp.model import Problem, Stop, Vehicle


def problem(
    dist: list[list[float | None]],
    *,
    demands: list[float] | None = None,
    service_s: list[float] | None = None,
    capacity: float = 100.0,
    duration: list[list[float | None]] | None = None,
) -> Problem:
    """None hücre = ulaşılamaz. Süre verilmezse mesafe / 10 (36 km/sa)."""
    n = len(dist) - 1
    d = _arr(dist)
    stops = tuple(
        Stop(
            id=101 + i,                       # kargo kimliği indeksle karışmasın
            node_id=1000 + i,
            demand_kg=demands[i] if demands else 1.0,
            service_time_s=service_s[i] if service_s else 0.0,
        )
        for i in range(n)
    )
    return Problem(
        depot_node_id=999,
        stops=stops,
        vehicles=(Vehicle(id=1, capacity_kg=capacity),),
        distance=d,
        duration=_arr(duration) if duration is not None else d / 10.0,
    )


def line(positions: list[float], **kw) -> Problem:
    """Depo x=0'da, duraklar bir doğru üzerinde: mesafe = |x_i - x_j|."""
    xs = [0.0, *positions]
    return problem([[abs(a - b) for b in xs] for a in xs], **kw)


def _arr(m: list[list[float | None]]) -> np.ndarray:
    return np.array([[math.inf if v is None else float(v) for v in r] for r in m], dtype=float)
