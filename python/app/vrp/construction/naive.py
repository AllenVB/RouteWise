"""
Naive baseline — duraklar geliş sırasıyla.

Listeyi yukarıdan aşağı okuyan bir dispatcher'ı temsil eder. Her koşuda optimize
çözümle birlikte hesaplanır; tasarruf yüzdesi buna göre ölçülür. Bu yüzden
"geliş sırası" anlamlı olmalıdır: backend kargoları oluşturulma sırasıyla
gönderir.
"""

from __future__ import annotations

from collections.abc import Sequence

from ..model import Problem, Route, Vehicle


def naive(problem: Problem, stops: Sequence[int], vehicle: Vehicle) -> Route:
    del problem   # sıralama girdiden bağımsız; imza diğer kurucularla aynı kalsın
    return Route(vehicle=vehicle, stops=list(stops))
