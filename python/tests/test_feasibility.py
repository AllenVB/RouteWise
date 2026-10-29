"""
Olurluluk testleri — algoritmalardan ÖNCE yazıldı (roadmap, değişmeyen kural 2).

Bu kontrol yanlışsa algoritma testlerinin yeşil olması hiçbir şey kanıtlamaz:
geçersiz rota sessizce geçer.
"""

from __future__ import annotations

import pytest

from app.vrp.feasibility import capacity_ok, drivable, route_load, violations
from app.vrp.model import Route, Solution
from tests.vrp_fixtures import line, problem


def tek(p, stops):
    return Solution(algorithm="TEST", routes=[Route(p.vehicles[0], list(stops))])


# --- kapasite ---

def test_kapasite_tam_sinirda_olurlu():
    p = line([1, 2], demands=[40.0, 60.0], capacity=100.0)
    assert capacity_ok(p, Route(p.vehicles[0], [1, 2]))


def test_kapasite_asilinca_olursuz():
    p = line([1, 2], demands=[40.0, 60.01], capacity=100.0)
    r = Route(p.vehicles[0], [1, 2])
    assert not capacity_ok(p, r)
    assert any("kapasite" in v for v in violations(p, tek(p, [1, 2]), [1, 2]))


def test_kayan_nokta_toplami_kapasiteyi_asmis_gostermez():
    # 0.1 + 0.2 = 0.30000000000000004
    p = line([1, 2], demands=[0.1, 0.2], capacity=0.3)
    assert capacity_ok(p, Route(p.vehicles[0], [1, 2]))


def test_yuk_toplami():
    p = line([1, 2, 3], demands=[1.5, 2.5, 4.0])
    assert route_load(p, Route(p.vehicles[0], [3, 1])) == pytest.approx(5.5)


# --- sürülebilirlik ---

def test_yolu_olmayan_bacak_olursuz():
    # 1 -> 2 yok (tek yön), 2 -> 1 var
    p = problem([
        [0, 1, 1],
        [1, 0, None],
        [1, 1, 0],
    ])
    assert drivable(p, Route(p.vehicles[0], [2, 1]))
    assert not drivable(p, Route(p.vehicles[0], [1, 2]))
    assert any("yolu olmayan" in v for v in violations(p, tek(p, [1, 2]), [1, 2]))


def test_depoya_donus_yolu_yoksa_olursuz():
    p = problem([
        [0, 1],
        [None, 0],
    ])
    assert not drivable(p, Route(p.vehicles[0], [1]))


def test_bos_rota_olurlu():
    p = line([1])
    assert drivable(p, Route(p.vehicles[0], []))
    assert capacity_ok(p, Route(p.vehicles[0], []))


# --- kapsama ---

def test_olurlu_cozumde_ihlal_yok():
    p = line([1, 2, 3])
    assert violations(p, tek(p, [1, 2, 3]), [1, 2, 3]) == []


def test_eksik_durak_yakalanir():
    p = line([1, 2, 3])
    assert violations(p, tek(p, [1, 3]), [1, 2, 3]) == ["ziyaret edilmeyen: [2]"]


def test_tekrar_ziyaret_yakalanir():
    p = line([1, 2])
    assert "birden fazla ziyaret: [1]" in violations(p, tek(p, [1, 2, 1]), [1, 2])


def test_beklenmeyen_durak_yakalanir():
    """Ulaşılamaz diye dışlanan durak rotaya sızmamalı."""
    p = line([1, 2])
    assert violations(p, tek(p, [1, 2]), [1]) == ["beklenmeyen durak: [2]"]


def test_depo_durak_olarak_yakalanir():
    """stop(0) sessizce son durağı döndürseydi bu ihlal görünmezdi."""
    p = line([1, 2])
    v = violations(p, tek(p, [1, 0, 2]), [1, 2])
    assert any("depo" in x for x in v)


def test_aralik_disi_indeks_yakalanir():
    p = line([1, 2])
    v = violations(p, tek(p, [1, 2, 7]), [1, 2])
    assert any("gecersiz durak indeksi [7]" in x for x in v)


def test_stop_sifir_indeksle_patlar():
    p = line([1, 2])
    with pytest.raises(IndexError):
        p.stop(0)
