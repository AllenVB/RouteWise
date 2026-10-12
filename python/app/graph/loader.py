"""
Yol grafını `.npz` dosyasından yükler ve CSR bitişikliğe çevirir.

Dosyayı Faz 0'daki `dataprep export` adımı üretir. Servis veritabanına
erişmez; graf değişmeyen referans veri olarak buradan okunur.
"""

from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from .model import RoadGraph

log = logging.getLogger(__name__)

REQUIRED_KEYS = (
    "edge_gid", "edge_source", "edge_target", "edge_length_m",
    "edge_cost", "edge_reverse_cost", "edge_cost_s", "edge_reverse_cost_s",
    "node_id", "node_lon", "node_lat",
)


def load_graph(path: str | Path) -> RoadGraph:
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(
            f"yol grafı bulunamadı: {path}\n"
            "Faz 0 veri hattı çalıştırılmalı: "
            "docker compose --profile tools run --rm dataprep all"
        )

    data = np.load(path)
    missing = [k for k in REQUIRED_KEYS if k not in data]
    if missing:
        raise ValueError(f"graf dosyasında eksik alanlar: {missing}")

    node_ids = data["node_id"].astype(np.int64)
    id_to_index = {int(nid): i for i, nid in enumerate(node_ids)}

    src = data["edge_source"].astype(np.int64)
    tgt = data["edge_target"].astype(np.int64)
    gid = data["edge_gid"].astype(np.int64)
    length = data["edge_length_m"].astype(np.float64)
    cost = data["edge_cost"].astype(np.float64)
    rcost = data["edge_reverse_cost"].astype(np.float64)
    cost_s = data["edge_cost_s"].astype(np.float64)
    rcost_s = data["edge_reverse_cost_s"].astype(np.float64)

    # Yönlü kenarları aç. Negatif maliyet = o yönde geçilemez (tek yönlü yol).
    # NaN da geçilemez sayılır: veri eksikse araç oradan geçemez varsayımı güvenlidir.
    fwd_ok = np.isfinite(cost) & (cost >= 0)
    bwd_ok = np.isfinite(rcost) & (rcost >= 0)

    rows: list[np.ndarray] = []
    cols: list[np.ndarray] = []
    lens: list[np.ndarray] = []
    durs: list[np.ndarray] = []
    gids: list[np.ndarray] = []

    def _append(mask: np.ndarray, a: np.ndarray, b: np.ndarray, dur: np.ndarray) -> None:
        if not mask.any():
            return
        rows.append(a[mask])
        cols.append(b[mask])
        lens.append(length[mask])
        # Süre bilgisi yoksa 0 yerine NaN bırakmak hesapları bozar; mesafeden
        # türetmek de yanıltıcı olur. Eksik süreyi işaretleyip 0'a çekiyoruz;
        # Faz 2'de hız varsayımı eklenecek.
        d = dur[mask]
        durs.append(np.where(np.isfinite(d) & (d >= 0), d, 0.0))
        gids.append(gid[mask])

    _append(fwd_ok, src, tgt, cost_s)
    _append(bwd_ok, tgt, src, rcost_s)

    if not rows:
        raise ValueError("grafta geçilebilir kenar yok")

    e_src = np.concatenate(rows)
    e_tgt = np.concatenate(cols)
    e_len = np.concatenate(lens)
    e_dur = np.concatenate(durs)
    e_gid = np.concatenate(gids)

    # Düğüm kimliklerini indekse çevir
    lookup = np.full(int(node_ids.max()) + 1, -1, dtype=np.int64)
    lookup[node_ids] = np.arange(len(node_ids), dtype=np.int64)
    si = lookup[e_src]
    ti = lookup[e_tgt]

    valid = (si >= 0) & (ti >= 0)
    dropped = int((~valid).sum())
    if dropped:
        log.warning("düğüm tablosunda karşılığı olmayan %d kenar atlandı", dropped)
    si, ti, e_len, e_dur, e_gid = si[valid], ti[valid], e_len[valid], e_dur[valid], e_gid[valid]

    # CSR'ye çevir: kaynağa göre sırala, sonra indptr üret
    order = np.argsort(si, kind="stable")
    si, ti = si[order], ti[order]
    e_len, e_dur, e_gid = e_len[order], e_dur[order], e_gid[order]

    n = len(node_ids)
    counts = np.bincount(si, minlength=n)
    indptr = np.zeros(n + 1, dtype=np.int64)
    np.cumsum(counts, out=indptr[1:])

    graph = RoadGraph(
        node_ids=node_ids,
        node_lon=data["node_lon"].astype(np.float64),
        node_lat=data["node_lat"].astype(np.float64),
        indptr=indptr,
        neighbors=ti,
        length_m=e_len,
        duration_s=e_dur,
        edge_gid=e_gid,
        _id_to_index=id_to_index,
    )

    log.info(
        "yol grafı yüklendi: %d düğüm, %d yönlü kenar, %.1f km",
        graph.node_count, graph.edge_count, np.sum(e_len) / 1000,
    )
    return graph
