"""Matris üretimi testleri."""

from __future__ import annotations

import numpy as np
import pytest

from app.graph.loader import load_graph
from app.matrix.builder import MAX_NODES, build_matrix, node_set_hash
from app.pathfinding.dijkstra import shortest_path


def graf(tmp_path, kenarlar, dugumler=None):
    src = [k[1] for k in kenarlar]
    tgt = [k[2] for k in kenarlar]
    nodes = dugumler or sorted({*src, *tgt})
    path = tmp_path / "m.npz"
    np.savez_compressed(
        path,
        edge_gid=np.array([k[0] for k in kenarlar], dtype=np.int64),
        edge_source=np.array(src, dtype=np.int64),
        edge_target=np.array(tgt, dtype=np.int64),
        edge_length_m=np.array([k[3] for k in kenarlar], dtype=float),
        edge_cost=np.array([k[3] for k in kenarlar], dtype=float),
        edge_reverse_cost=np.array([k[3] if k[4] else -1.0 for k in kenarlar], dtype=float),
        edge_cost_s=np.array([k[5] for k in kenarlar], dtype=float),
        edge_reverse_cost_s=np.array([k[5] if k[4] else -1.0 for k in kenarlar], dtype=float),
        node_id=np.array(nodes, dtype=np.int64),
        node_lon=np.zeros(len(nodes)),
        node_lat=np.zeros(len(nodes)),
    )
    return load_graph(path)


# (gid, kaynak, hedef, uzunluk_m, cift_yonlu, sure_s)
ZINCIR = [
    (1, 1, 2, 100.0, True, 10.0),
    (2, 2, 3, 100.0, True, 10.0),
]


def test_kosegen_sifir(tmp_path):
    g = graf(tmp_path, ZINCIR)
    m = build_matrix(g, [1, 2, 3])
    for i in range(3):
        assert m["distanceM"][i][i] == 0.0
        assert m["durationS"][i][i] == 0.0


def test_mesafeler_dogru(tmp_path):
    g = graf(tmp_path, ZINCIR)
    m = build_matrix(g, [1, 2, 3])
    i = m["nodeIds"].index
    assert m["distanceM"][i(1)][i(3)] == pytest.approx(200.0)
    assert m["distanceM"][i(1)][i(2)] == pytest.approx(100.0)
    assert m["durationS"][i(1)][i(3)] == pytest.approx(20.0)


def test_dijkstra_ile_tutarli(tmp_path):
    """Matris ile tek tek sorgulama aynı sonucu vermeli."""
    g = graf(tmp_path, ZINCIR)
    m = build_matrix(g, [1, 2, 3])
    for a in (1, 2, 3):
        for b in (1, 2, 3):
            beklenen = shortest_path(g, a, b).length_m
            alinan = m["distanceM"][m["nodeIds"].index(a)][m["nodeIds"].index(b)]
            assert alinan == pytest.approx(beklenen), f"{a}->{b}"


def test_kanonik_sira_uygulanir(tmp_path):
    """Girdi sırası ne olursa olsun matris sıralı düğüm listesini izler."""
    g = graf(tmp_path, ZINCIR)
    m = build_matrix(g, [3, 1, 2])
    assert m["nodeIds"] == [1, 2, 3]


def test_tekrar_eden_dugum_tekillestirilir(tmp_path):
    g = graf(tmp_path, ZINCIR)
    m = build_matrix(g, [1, 1, 2, 2, 3])
    assert m["nodeIds"] == [1, 2, 3]
    assert len(m["distanceM"]) == 3


def test_ulasilamayan_cift_null_doner(tmp_path):
    """
    Sonsuzluk büyük bir sayıyla değiştirilmez: optimizasyon onu 'pahalı ama
    gidilebilir' sanıp olursuz rota üretirdi.
    """
    g = graf(tmp_path,
             [(1, 1, 2, 100.0, True, 10.0), (2, 3, 4, 100.0, True, 10.0)],
             dugumler=[1, 2, 3, 4])
    m = build_matrix(g, [1, 2, 3, 4])
    i = m["nodeIds"].index
    assert m["distanceM"][i(1)][i(4)] is None
    assert m["unreachablePairs"] > 0


def test_tek_yonlu_yol_matriste_asimetrik(tmp_path):
    g = graf(tmp_path, [(1, 1, 2, 100.0, False, 10.0)])   # yalnızca 1->2
    m = build_matrix(g, [1, 2])
    i = m["nodeIds"].index
    assert m["distanceM"][i(1)][i(2)] == pytest.approx(100.0)
    assert m["distanceM"][i(2)][i(1)] is None


def test_grafta_olmayan_dugum_hata(tmp_path):
    g = graf(tmp_path, ZINCIR)
    with pytest.raises(KeyError, match="grafta olmayan"):
        build_matrix(g, [1, 2, 999])


def test_bos_liste_hata(tmp_path):
    g = graf(tmp_path, ZINCIR)
    with pytest.raises(ValueError, match="boş"):
        build_matrix(g, [])


def test_cok_fazla_dugum_hata(tmp_path):
    g = graf(tmp_path, ZINCIR)
    with pytest.raises(ValueError, match="en fazla"):
        build_matrix(g, list(range(1, MAX_NODES + 50)))


# --- cache anahtarı ---

def test_hash_sira_bagimsiz():
    assert node_set_hash([3, 1, 2]) == node_set_hash([1, 2, 3])


def test_hash_tekrar_bagimsiz():
    assert node_set_hash([1, 2, 2, 3]) == node_set_hash([1, 2, 3])


def test_hash_farkli_kume_farkli():
    assert node_set_hash([1, 2, 3]) != node_set_hash([1, 2, 4])


def test_hash_bicimi():
    h = node_set_hash([1, 2, 3])
    assert len(h) == 64 and h == h.lower()


def test_hash_bilinen_deger():
    """
    Backend ile paylaşılan sabit. Java tarafında NodeSetHashTest aynı değeri
    sınar; biri değişirse cache sessizce ıskalar ve matris her seferinde
    yeniden hesaplanır — kimse fark etmez, sadece yavaşlar.
    """
    assert node_set_hash([1, 2, 3]) == (
        "8a6ae15122001229edb8866f56e342af12ae8187203c3e3b33931743e7c0c48d")
