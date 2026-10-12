"""
Graf yükleyici testleri.

Gerçek graf dosyasına bağımlı değildir: sentetik bir .npz üretir. Böylece test
Faz 0 veri hattı çalıştırılmamış bir makinede de koşar.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.graph.loader import load_graph


def _write_npz(tmp_path, **overrides):
    """Küçük bir test grafı: 1 --10m--> 2 --20m--> 3, 2->1 tek yönlü kapalı."""
    data = dict(
        edge_gid=np.array([101, 102], dtype=np.int64),
        edge_source=np.array([1, 2], dtype=np.int64),
        edge_target=np.array([2, 3], dtype=np.int64),
        edge_length_m=np.array([10.0, 20.0]),
        # 1->2 çift yönlü, 2->3 tek yönlü (reverse_cost negatif)
        edge_cost=np.array([10.0, 20.0]),
        edge_reverse_cost=np.array([10.0, -1.0]),
        edge_cost_s=np.array([2.0, 4.0]),
        edge_reverse_cost_s=np.array([2.0, -1.0]),
        node_id=np.array([1, 2, 3], dtype=np.int64),
        node_lon=np.array([27.97, 27.98, 27.99]),
        node_lat=np.array([40.35, 40.36, 40.37]),
    )
    data.update(overrides)
    path = tmp_path / "g.npz"
    np.savez_compressed(path, **data)
    return path


def test_yukleme_dugum_ve_kenar_sayisi(tmp_path):
    g = load_graph(_write_npz(tmp_path))
    assert g.node_count == 3
    # 1<->2 iki yönlü (2 kenar) + 2->3 tek yönlü (1 kenar) = 3
    assert g.edge_count == 3


def test_tek_yonlu_yol_ters_yone_acilmaz(tmp_path):
    g = load_graph(_write_npz(tmp_path))
    i3 = g.index_of(3)
    komsular, _, _ = g.neighbors_of(i3)
    # 3'ten çıkan kenar yok: 2->3 tek yönlüydü
    assert len(komsular) == 0


def test_cift_yonlu_yol_her_iki_yone_acilir(tmp_path):
    g = load_graph(_write_npz(tmp_path))
    i1, i2 = g.index_of(1), g.index_of(2)
    assert i2 in g.neighbors_of(i1)[0]
    assert i1 in g.neighbors_of(i2)[0]


def test_mesafe_ve_sure_tasinir(tmp_path):
    g = load_graph(_write_npz(tmp_path))
    i1 = g.index_of(1)
    komsular, mesafeler, sureler = g.neighbors_of(i1)
    assert mesafeler[0] == pytest.approx(10.0)
    assert sureler[0] == pytest.approx(2.0)


def test_eksik_sure_sifira_cekilir(tmp_path):
    """Süre NaN ise hesap bozulmasın diye 0'a çekilir — mesafeden türetilmez."""
    p = _write_npz(tmp_path, edge_cost_s=np.array([np.nan, 4.0]))
    g = load_graph(p)
    i1 = g.index_of(1)
    _, _, sureler = g.neighbors_of(i1)
    assert sureler[0] == 0.0


def test_road_km_cift_yonlu_yolu_iki_kez_saymaz(tmp_path):
    """
    1<->2 (10 m, çift yönlü) + 2->3 (20 m, tek yönlü) = fiziksel 30 m.
    Yönlü kenarların toplamı 40 m olurdu; road_km bunu yapmamalı.
    """
    g = load_graph(_write_npz(tmp_path))
    assert float(np.sum(g.length_m)) == pytest.approx(40.0)   # yönlü toplam
    assert g.road_km() == pytest.approx(0.030)                # fiziksel uzunluk


def test_dosya_yoksa_acik_hata(tmp_path):
    with pytest.raises(FileNotFoundError, match="yol grafı bulunamadı"):
        load_graph(tmp_path / "yok.npz")


def test_eksik_alan_varsa_hata(tmp_path):
    path = tmp_path / "bozuk.npz"
    np.savez_compressed(path, node_id=np.array([1]))
    with pytest.raises(ValueError, match="eksik alanlar"):
        load_graph(path)


def test_bilinmeyen_dugum_kimligi(tmp_path):
    g = load_graph(_write_npz(tmp_path))
    with pytest.raises(KeyError, match="grafta yok"):
        g.index_of(999)
