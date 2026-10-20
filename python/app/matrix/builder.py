"""
Mesafe ve süre matrisi üretimi.

Graf Python servisinin belleğinde olduğu için matris burada hesaplanır; backend
sonucu `distance_matrix_cache` tablosunda saklar. (Mimari §7 — "Matrisi kim
hesaplar".)

N×N matris için N kez **tek kaynaklı** Dijkstra koşulur. N² kez kaynak-hedef
sorgusu yapmak aynı işi onlarca kat pahalıya mal ederdi: tek kaynak koşusu zaten
tüm düğümlere mesafeyi üretir.
"""

from __future__ import annotations

import hashlib
import math

import numpy as np

from ..graph.model import RoadGraph
from ..pathfinding.base import Timer, Weight
from ..pathfinding.dijkstra import single_source

# Tek istekte izin verilen en fazla düğüm. 300 düğüm = 90.000 hücre; bunun
# üstü hem yanıtı şişirir hem de bir hatanın işareti olur.
MAX_NODES = 300


def node_set_hash(node_ids: list[int]) -> str:
    """
    Düğüm kümesinin kanonik parmak izi — cache anahtarı.

    Tanım (backend ile birebir aynı olmalı):
      sıralı, tekilleştirilmiş kimlikler, virgülle birleştirilir,
      UTF-8 olarak SHA-256'ya verilir, küçük harf hex döner.
    """
    kanonik = ",".join(str(n) for n in sorted(set(node_ids)))
    return hashlib.sha256(kanonik.encode("utf-8")).hexdigest()


def build_matrix(graph: RoadGraph, node_ids: list[int]) -> dict:
    """Verilen düğümler arasında tam mesafe/süre matrisi."""
    if not node_ids:
        raise ValueError("düğüm listesi boş")
    if len(set(node_ids)) > MAX_NODES:
        raise ValueError(f"en fazla {MAX_NODES} düğüm desteklenir, {len(set(node_ids))} geldi")

    # Kanonik sıra: matris satır/sütunları bu sırayı izler ve hash'le tutarlıdır.
    kanonik = sorted(set(node_ids))
    eksik = [n for n in kanonik if n not in graph._id_to_index]
    if eksik:
        raise KeyError(f"grafta olmayan düğümler: {eksik[:10]}")

    n = len(kanonik)
    hedef_idx = np.array([graph.index_of(x) for x in kanonik], dtype=np.int64)

    mesafe = np.zeros((n, n))
    sure = np.zeros((n, n))

    with Timer() as timer:
        for i, kaynak in enumerate(kanonik):
            d_all, s_all = single_source(graph, kaynak, Weight.DISTANCE)
            mesafe[i, :] = d_all[hedef_idx]
            sure[i, :] = s_all[hedef_idx]

    ulasilamaz = int(np.isinf(mesafe).sum())

    return {
        "nodeSetHash": node_set_hash(kanonik),
        "nodeIds": kanonik,
        "distanceM": _json_safe(mesafe),
        "durationS": _json_safe(sure),
        "unreachablePairs": ulasilamaz,
        "computationTimeMs": round(timer.ms, 2),
    }


def _json_safe(m: np.ndarray) -> list[list[float | None]]:
    """
    inf -> null.

    Sonsuzluğu büyük bir sayıyla değiştirmiyoruz: optimizasyon onu "pahalı ama
    gidilebilir" sanır ve olursuz rota üretir. null açıkça "yol yok" demektir.
    """
    out: list[list[float | None]] = []
    for satir in m:
        out.append([None if not math.isfinite(v) else round(float(v), 2) for v in satir])
    return out
