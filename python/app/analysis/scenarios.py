"""
Senaryo üretici — anlamlılık testinin girdisi (roadmap Faz 6, risk: "yeterli senaryo").

Bir senaryo: depo + `stop_count` teslimat noktası, gerçek Bandırma yol grafından.
Noktalar depoya `radius_m` kuş uçuşu mesafe içindeki düğümlerden **tohumla**
seçilir; aynı tohum her zaman aynı senaryoyu verir (rapor tekrar üretilebilir).
Yük, teslim süresi ve öncelik de tohumdan gelir; dağılımlar seed verisindeki
kargolara benzer tutulur (2–8 kg, 2–5 dk, öncelik 0–3).

Depoyla gidiş-dönüş yolu olmayan düğüm (tek yönlü çıkmaz, ayrık parça) seçilmez:
senaryo karşılaştırmayı ölçmeli, ulaşılamazlık süzgecini değil.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

import numpy as np

from ..graph.model import RoadGraph
from ..pathfinding.dijkstra import single_source
from ..pathfinding.base import Weight

EARTH_RADIUS_M = 6_371_008.8


@dataclass(frozen=True)
class ScenarioStop:
    id: int
    node_id: int
    demand_kg: float
    service_time_s: float
    priority: int


def _distances_from(graph: RoadGraph, i: int) -> np.ndarray:
    lat = np.radians(graph.node_lat)
    lon = np.radians(graph.node_lon)
    dlat = lat - lat[i]
    dlon = lon - lon[i]
    a = np.sin(dlat / 2) ** 2 + np.cos(lat) * math.cos(lat[i]) * np.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * np.arcsin(np.minimum(1.0, np.sqrt(a)))


def tour_scenario(graph: RoadGraph, depot_node_id: int, seed: int, stop_count: int,
                  radius_m: float = 3000.0) -> list[ScenarioStop]:
    """
    ValueError: yarıçap içinde yeterli ulaşılabilir düğüm yok.
    KeyError: depo düğümü grafta yok.
    """
    d = graph.index_of(depot_node_id)
    near = np.flatnonzero(_distances_from(graph, d) <= radius_m)
    # Gidiş: depodan düğüme; dönüş: düğümden depoya. Ters yön için grafın tersi
    # yok; dönüşü her aday için ayrı koşmak yerine düğümden depoya en kısa yolu
    # yalnızca seçilen adaylarda kontrol ederiz.
    out_dist, _ = single_source(graph, depot_node_id, Weight.DISTANCE)
    candidates = sorted(int(graph.node_ids[i]) for i in near if i != d and np.isfinite(out_dist[i]))

    rng = random.Random(seed)
    rng.shuffle(candidates)
    chosen: list[int] = []
    for node in candidates:
        back, _ = single_source(graph, node, Weight.DISTANCE)
        if np.isfinite(back[d]):
            chosen.append(node)
            if len(chosen) == stop_count:
                break
    if len(chosen) < stop_count:
        raise ValueError(f"{radius_m:.0f} m içinde {stop_count} ulaşılabilir düğüm yok ({len(chosen)} bulundu)")

    return [
        ScenarioStop(
            id=k + 1,
            node_id=node,
            demand_kg=round(rng.uniform(2.0, 8.0), 1),
            service_time_s=float(rng.choice([120, 180, 240, 300])),
            priority=rng.choice([0, 0, 0, 1, 1, 2, 3]),
        )
        for k, node in enumerate(chosen)
    ]
