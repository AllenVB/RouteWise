"""
Yerel arama sürücüsü — ilk iyileştirme (first improvement), yerel optimuma kadar.

Operatörler sırayla dolaşılır (round-robin). İyileştirme bulan operatörde
kalınır ve o operatör yeniden taranır; iyileştirme bulamayan sıradakine geçer.
Art arda bütün operatörler iyileştirme bulamayınca çözüm yerel optimumdadır.

İlk sürüm her iyileştirmeden sonra 2-opt'tan baştan başlıyordu; n=100'de tek
bir tam tarama ~1 sn sürdüğü için yerel arama ~4 sn'ye çıkıyordu.
Deterministiktir: aynı girdi aynı çıktı.
"""

from __future__ import annotations

from collections.abc import Callable, Iterator

from . import exchange, or_opt, relocate, two_opt
from .state import Changes, RouteSet

Neighborhood = Callable[[RouteSet], Iterator[Changes]]

OPERATORS: tuple[Neighborhood, ...] = (
    two_opt.neighbors,
    relocate.neighbors,
    exchange.neighbors,
    or_opt.neighbors,
)

# Kayan nokta gürültüsüyle sonsuz döngüye girmemek için anlamlı iyileştirme eşiği.
MIN_GAIN = 1e-6


def local_search(rs: RouteSet, operators: tuple[Neighborhood, ...] = OPERATORS,
                 max_moves: int = 100_000) -> int:
    """Çözümü yerinde iyileştirir; uygulanan hamle sayısını döner."""
    moves = 0
    idle = 0           # art arda iyileştirme bulamayan operatör sayısı
    k = 0
    while idle < len(operators) and moves < max_moves:
        op = operators[k % len(operators)]
        for changes in op(rs):
            d = rs.delta(changes)
            if d is not None and d < -MIN_GAIN:
                rs.apply(changes)
                moves += 1
                idle = 0
                break          # aynı operatörü güncel çözüm üzerinde yeniden tara
        else:
            idle += 1
            k += 1
    return moves
