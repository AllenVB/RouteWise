#!/usr/bin/env python3
"""
Kendi Dijkstra'mızı rastgele düğüm çiftlerinde koşturur ve CSV basar.

pgRouting doğrulaması için kullanılır: aynı çiftler `pgr_dijkstraCost` ile de
hesaplanır, iki çıktı karşılaştırılır. Tohum sabit olduğu için çiftler
tekrarlanabilir.

  python3 -m tools.dijkstra_pairs [adet] [tohum]
"""

from __future__ import annotations

import sys

import numpy as np

from app.config import settings
from app.graph.loader import load_graph
from app.pathfinding.dijkstra import shortest_path

adet = int(sys.argv[1]) if len(sys.argv) > 1 else 25
tohum = int(sys.argv[2]) if len(sys.argv) > 2 else 42

g = load_graph(settings.graph_path)

# Yalnızca çıkan kenarı olan düğümlerden seç: izole düğüm çifti
# karşılaştırmayı bilgilendirmez, ikisi de "yol yok" der.
dereceler = np.diff(g.indptr)
aday = g.node_ids[dereceler > 0]

rng = np.random.default_rng(tohum)
kaynaklar = rng.choice(aday, size=adet, replace=False)
hedefler = rng.choice(aday, size=adet, replace=False)

print("source,target,length_m,found,nodes_visited,ms")
for s, t in zip(kaynaklar, hedefler):
    r = shortest_path(g, int(s), int(t))
    print(f"{int(s)},{int(t)},{r.length_m:.4f},{int(r.found)},"
          f"{r.nodes_visited},{r.computation_time_ms:.2f}")
