"""
Dijkstra testleri — bilinen cevabı olan küçük graflarla.

Gerçek Bandırma grafına bağımlı değildir; pgRouting karşılaştırması ayrı bir
doğrulama adımıdır (infra/dataprep verify).
"""

from __future__ import annotations

import numpy as np
import pytest

from app.graph.loader import load_graph
from app.pathfinding.base import Weight
from app.pathfinding.dijkstra import shortest_path, single_source


def graf(tmp_path, *, kenarlar, dugumler=None, ad="g.npz"):
    """
    kenarlar: (gid, kaynak, hedef, uzunluk_m, ileri_ok, geri_ok, sure_s)
    ileri/geri_ok False ise o yön kapalı (negatif maliyet).
    """
    gid, src, tgt, ln, cs, rcs = [], [], [], [], [], []
    for g, a, b, m, f, r, s in kenarlar:
        gid.append(g); src.append(a); tgt.append(b); ln.append(m)
        cs.append(s if f else -1.0)
        rcs.append(s if r else -1.0)
    ln = np.array(ln, dtype=float)
    path = tmp_path / ad
    np.savez_compressed(
        path,
        edge_gid=np.array(gid, dtype=np.int64),
        edge_source=np.array(src, dtype=np.int64),
        edge_target=np.array(tgt, dtype=np.int64),
        edge_length_m=ln,
        edge_cost=np.array([m if f else -1.0 for (_, _, _, m, f, _, _) in kenarlar]),
        edge_reverse_cost=np.array([m if r else -1.0 for (_, _, _, m, _, r, _) in kenarlar]),
        edge_cost_s=np.array(cs),
        edge_reverse_cost_s=np.array(rcs),
        node_id=np.array(dugumler or sorted({*src, *tgt}), dtype=np.int64),
        node_lon=np.zeros(len(dugumler or {*src, *tgt})),
        node_lat=np.zeros(len(dugumler or {*src, *tgt})),
    )
    return load_graph(path)


# 1 --100m-- 2 --100m-- 3   ve   1 --------250m-------- 3
# En kısa: 1-2-3 = 200 m
UCGEN = [
    (1, 1, 2, 100.0, True, True, 10.0),
    (2, 2, 3, 100.0, True, True, 10.0),
    (3, 1, 3, 250.0, True, True, 25.0),
]


def test_en_kisa_yolu_bulur(tmp_path):
    g = graf(tmp_path, kenarlar=UCGEN)
    r = shortest_path(g, 1, 3)
    assert r.found
    assert r.length_m == pytest.approx(200.0)
    assert r.path == [1, 2, 3]


def test_dogrudan_kenar_daha_kisaysa_onu_secer(tmp_path):
    g = graf(tmp_path, kenarlar=[
        (1, 1, 2, 100.0, True, True, 10.0),
        (2, 2, 3, 100.0, True, True, 10.0),
        (3, 1, 3, 150.0, True, True, 15.0),
    ])
    r = shortest_path(g, 1, 3)
    assert r.length_m == pytest.approx(150.0)
    assert r.path == [1, 3]


def test_sureye_gore_en_kisa_farkli_olabilir(tmp_path):
    """Mesafe ve süre farklı yollar seçtirebilir — bu projenin bütün meselesi."""
    g = graf(tmp_path, kenarlar=[
        # uzun ama hızlı
        (1, 1, 2, 300.0, True, True, 10.0),
        (2, 2, 3, 300.0, True, True, 10.0),
        # kısa ama yavaş
        (3, 1, 3, 400.0, True, True, 90.0),
    ])
    mesafe = shortest_path(g, 1, 3, Weight.DISTANCE)
    sure = shortest_path(g, 1, 3, Weight.DURATION)
    assert mesafe.path == [1, 3]
    assert mesafe.length_m == pytest.approx(400.0)
    assert sure.path == [1, 2, 3]
    assert sure.duration_s == pytest.approx(20.0)


def test_ikinci_olcu_de_tasinir(tmp_path):
    """Mesafeye göre arandığında süre de doğru toplanmalı."""
    g = graf(tmp_path, kenarlar=UCGEN)
    r = shortest_path(g, 1, 3)
    assert r.length_m == pytest.approx(200.0)
    assert r.duration_s == pytest.approx(20.0)


def test_tek_yonlu_yol_ters_yone_gidilemez(tmp_path):
    g = graf(tmp_path, kenarlar=[
        (1, 1, 2, 100.0, True, False, 10.0),   # yalnızca 1->2
    ])
    assert shortest_path(g, 1, 2).found
    assert not shortest_path(g, 2, 1).found


def test_ulasilamayan_hedef(tmp_path):
    g = graf(tmp_path,
             kenarlar=[(1, 1, 2, 100.0, True, True, 10.0),
                       (2, 3, 4, 100.0, True, True, 10.0)],
             dugumler=[1, 2, 3, 4])
    r = shortest_path(g, 1, 4)
    assert not r.found
    assert r.length_m == 0.0


def test_kaynak_hedefe_esit(tmp_path):
    g = graf(tmp_path, kenarlar=UCGEN)
    r = shortest_path(g, 2, 2)
    assert r.found and r.length_m == pytest.approx(0.0) and r.path == [2]


def test_olculer_raporlanir(tmp_path):
    g = graf(tmp_path, kenarlar=UCGEN)
    r = shortest_path(g, 1, 3)
    assert r.nodes_visited > 0
    assert r.computation_time_ms >= 0.0
    d = r.as_dict()
    assert d["lengthM"] == pytest.approx(200.0)
    assert set(d) == {"found", "path", "lengthM", "durationS", "nodesVisited", "computationTimeMs"}


def test_tek_kaynak_tum_dugumlere(tmp_path):
    g = graf(tmp_path, kenarlar=UCGEN)
    mesafe, sure = single_source(g, 1)
    assert mesafe[g.index_of(1)] == pytest.approx(0.0)
    assert mesafe[g.index_of(2)] == pytest.approx(100.0)
    assert mesafe[g.index_of(3)] == pytest.approx(200.0)
    assert sure[g.index_of(3)] == pytest.approx(20.0)


def test_tek_kaynak_ulasilamayan_icin_sonsuz(tmp_path):
    g = graf(tmp_path,
             kenarlar=[(1, 1, 2, 100.0, True, True, 10.0),
                       (2, 3, 4, 100.0, True, True, 10.0)],
             dugumler=[1, 2, 3, 4])
    mesafe, _ = single_source(g, 1)
    assert np.isinf(mesafe[g.index_of(4)])


def test_tek_kaynak_ve_ciftli_sorgu_ayni_sonucu_verir(tmp_path):
    """single_source matris için kullanılacak; shortest_path ile tutarlı olmalı."""
    g = graf(tmp_path, kenarlar=UCGEN)
    mesafe, _ = single_source(g, 1)
    for hedef in (2, 3):
        assert mesafe[g.index_of(hedef)] == pytest.approx(
            shortest_path(g, 1, hedef).length_m)
