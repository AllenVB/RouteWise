"""
Yol grafının bellek içi temsili.

Tasarım notu — neden CSR benzeri bitişiklik:
Dijkstra her çalıştığında bir düğümün komşularını gezer. Python sözlükleri yerine
bitişik numpy dizileri kullanmak, hem bellek hem erişim hızı açısından belirgin
fark yaratır; 9.797 düğümlü bir grafta matris üretirken bu fark büyür.

Yön bilgisi: osm2pgrouting'de `cost` / `reverse_cost` alanlarında NEGATİF değer
"o yönde geçilemez" demektir (tek yönlü yol). Kenarlar bu işarete göre açılır.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class RoadGraph:
    """Yönlü yol grafı, CSR (compressed sparse row) düzeninde."""

    # Düğümler — indeks sırası kanoniktir; node_id -> indeks için index_of()
    node_ids: np.ndarray      # int64[n]
    node_lon: np.ndarray      # float64[n]
    node_lat: np.ndarray      # float64[n]

    # CSR bitişiklik: indptr[i]..indptr[i+1] aralığı i düğümünün çıkan kenarları
    indptr: np.ndarray        # int64[n+1]
    neighbors: np.ndarray     # int64[m]  hedef düğüm indeksi
    length_m: np.ndarray      # float64[m]
    duration_s: np.ndarray    # float64[m]
    edge_gid: np.ndarray      # int64[m]  izlenebilirlik için ways.gid

    _id_to_index: dict[int, int]

    @property
    def node_count(self) -> int:
        return len(self.node_ids)

    @property
    def edge_count(self) -> int:
        return len(self.neighbors)

    def index_of(self, node_id: int) -> int:
        """OSM/pgRouting düğüm kimliğinden dahili indekse."""
        try:
            return self._id_to_index[int(node_id)]
        except KeyError as exc:
            raise KeyError(f"düğüm grafta yok: {node_id}") from exc

    def neighbors_of(self, index: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Bir düğümün (hedef indeksleri, mesafeler, süreler) üçlüsü."""
        start, end = self.indptr[index], self.indptr[index + 1]
        return (
            self.neighbors[start:end],
            self.length_m[start:end],
            self.duration_s[start:end],
        )

    def road_km(self) -> float:
        """
        Fiziksel yol ağı uzunluğu (km).

        DİKKAT: length_m toplamı bunu vermez. Çift yönlü her yol iki yönlü
        kenara açıldığı için uzunluğu iki kez sayılır. Fiziksel uzunluk için
        kenarlar kaynak yol parçasına (ways.gid) göre tekilleştirilir.
        """
        _, first = np.unique(self.edge_gid, return_index=True)
        return float(np.sum(self.length_m[first])) / 1000

    def summary(self) -> dict:
        return {
            "node_count": self.node_count,
            "directed_edge_count": self.edge_count,
            "road_km": round(self.road_km(), 1),
            "bbox_lon": [round(float(self.node_lon.min()), 4), round(float(self.node_lon.max()), 4)],
            "bbox_lat": [round(float(self.node_lat.min()), 4), round(float(self.node_lat.max()), 4)],
        }
