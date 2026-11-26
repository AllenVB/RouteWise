"""
Simulated annealing — tohumlanmış, sıcak başlangıca uygun.

Her adımda rastgele bir operatörden rastgele bir komşu üretilir. Olursuz komşu
`RouteSet.delta` tarafından reddedilir ve hiç uygulanmaz; SA yalnızca olurlu
çözümler arasında dolaşır. Kötüleştiren hamle exp(-Δ/T) olasılıkla kabul edilir.

Belirlenimcilik: tüm rastgelelik tek bir `random.Random(seed)` kaynağından gelir
ve bütçe süre değil **iterasyon** sayısıdır. Aynı tohum + aynı girdi = aynı rota;
süre tabanlı bütçe makine hızına göre farklı sonuç verirdi ve tez ölçümü
tekrarlanamazdı.

Başlangıç sıcaklığı veriden ayarlanır: örneklenen kötüleştiren hamlelerin
ortalaması %50 olasılıkla kabul edilecek şekilde. Sıcak başlangıçta (önceki
plandan devam) sıcaklık düşük tutulur ki tek bir yeni kargo rotaları baştan
karıştırmasın.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

from ..localsearch import exchange, or_opt, relocate, two_opt
from ..localsearch.state import RouteSet

RANDOM_OPERATORS = (
    two_opt.random_neighbor,
    relocate.random_neighbor,
    exchange.random_neighbor,
    or_opt.random_neighbor,
)


@dataclass(frozen=True)
class AnnealParams:
    iterations: int = 20_000
    end_ratio: float = 1e-3          # son sıcaklık = başlangıç × oran
    initial_acceptance: float = 0.5  # ortalama kötüleşmenin başlangıçta kabul olasılığı
    warm_factor: float = 0.05        # sıcak başlangıçta sıcaklık çarpanı
    calibration_samples: int = 200


def anneal(rs: RouteSet, rng: random.Random, params: AnnealParams = AnnealParams(),
           warm: bool = False) -> int:
    """`rs`'i yerinde bulunan en iyi çözüme getirir; kabul edilen hamle sayısını döner."""
    if params.iterations <= 0:
        return 0
    t0 = _initial_temperature(rs, rng, params)
    if t0 <= 0:
        return 0                      # hiç olurlu kötüleşme yok: arama uzayı kilitli
    if warm:
        t0 *= params.warm_factor
    alpha = params.end_ratio ** (1.0 / params.iterations)

    best_routes = [list(r) for r in rs.routes]
    best_total = rs.total
    temperature = t0
    accepted = 0

    for _ in range(params.iterations):
        op = rng.choice(RANDOM_OPERATORS)
        changes = op(rs, rng)
        temperature *= alpha
        if changes is None:
            continue
        d = rs.delta(changes)
        if d is None:
            continue
        if d < 0 or rng.random() < math.exp(-d / temperature):
            rs.apply(changes)
            accepted += 1
            if rs.total < best_total - 1e-9:
                best_total = rs.total
                best_routes = [list(r) for r in rs.routes]

    rs.apply(dict(enumerate(best_routes)))
    return accepted


def _initial_temperature(rs: RouteSet, rng: random.Random, params: AnnealParams) -> float:
    """Kötüleştiren olurlu komşuların ortalamasına göre; durumu değiştirmez."""
    uphill: list[float] = []
    for _ in range(params.calibration_samples):
        changes = rng.choice(RANDOM_OPERATORS)(rs, rng)
        if changes is None:
            continue
        d = rs.delta(changes)
        if d is not None and d > 0:
            uphill.append(d)
    if not uphill:
        return 0.0
    mean = sum(uphill) / len(uphill)
    return -mean / math.log(params.initial_acceptance)
