"""
Faz 2 — tek araç tur sırası: kurucular, metrikler ve çözücü.

Her algoritma testi sonucu olurluluk kontrolünden de geçirir; "doğru sırayı
buldu" iddiası geçersiz bir rota üzerinde anlamsızdır.
"""

from __future__ import annotations

import dataclasses

import numpy as np
import pytest

from app.vrp.construction.naive import naive
from app.vrp.construction.nearest_neighbor import StuckError, nearest_neighbor
from app.vrp.feasibility import violations
from app.vrp.model import DEPOT, Route, Solution, Vehicle
from app.vrp.objective import route_metrics, total_distance
from app.vrp.solve import UNREACHABLE, Algorithm, InfeasibleError, solve
from tests.vrp_fixtures import line, problem


def arac(p):
    return p.vehicles[0]


def hepsi(p):
    return list(range(1, p.size + 1))


def olurlu(p, route):
    return violations(p, Solution("TEST", [route]), route.stops) == []


# Duraklar: 1@x=5, 2@x=1, 3@x=3 -> NN sırası 2,3,1 (toplam 10 m); naive 1,2,3 (14 m)
DOGRU = [5, 1, 3]


# --- nearest neighbor ---

def test_nn_en_yakini_secer():
    p = line(DOGRU)
    r = nearest_neighbor(p, hepsi(p), arac(p))
    assert r.stops == [2, 3, 1]
    assert olurlu(p, r)


def test_nn_asimetrik_matriste_gidis_yonunu_kullanir():
    """
    0->2 (3 m) 0->1'den (5 m) yakın. Ters yön (1->0 = 1 m) okunsaydı 1 seçilirdi.
    Tek yönlü sokaklarda bu hata rotayı sessizce uzatır.
    """
    p = problem([
        [0, 5, 3],
        [1, 0, 1],
        [9, 9, 0],
    ])
    assert nearest_neighbor(p, hepsi(p), arac(p)).stops == [2, 1]


def test_nn_esitlikte_aday_sirasina_uyar():
    p = line([2, -2])                      # ikisi de depoya 2 m
    assert nearest_neighbor(p, [1, 2], arac(p)).stops == [1, 2]
    assert nearest_neighbor(p, [2, 1], arac(p)).stops == [2, 1]


def test_nn_ayni_dugumdeki_iki_kargo_ardisik():
    """Aynı apartmana iki kargo: aralarındaki bacak 0 m."""
    p = line([4, 9, 4])
    r = nearest_neighbor(p, hepsi(p), arac(p))
    assert r.stops[:2] == [1, 3]
    assert route_metrics(p, r).legs[1].distance_m == 0.0


def test_nn_yol_yoksa_acik_hata():
    p = problem([
        [0, 1, 5],
        [1, 0, None],
        [1, None, 0],
    ])
    with pytest.raises(StuckError):
        nearest_neighbor(p, hepsi(p), arac(p))


def test_nn_bos_aday_listesi_bos_rota():
    p = line([1])
    assert nearest_neighbor(p, [], arac(p)).stops == []


@pytest.mark.parametrize("seed", range(10))
def test_nn_ve_naive_rastgele_matriste_olurlu(seed):
    """Her durak tam bir kez — asimetrik rastgele matrislerde de."""
    rng = np.random.default_rng(seed)
    n = 12
    m = rng.uniform(10, 1000, size=(n + 1, n + 1))
    np.fill_diagonal(m, 0)
    p = problem(m.tolist(), demands=rng.uniform(1, 5, n).tolist())
    for kur in (nearest_neighbor, naive):
        r = kur(p, hepsi(p), arac(p))
        assert sorted(r.stops) == hepsi(p)
        assert olurlu(p, r)


def test_nn_ayni_girdide_ayni_tur():
    rng = np.random.default_rng(7)
    m = rng.uniform(10, 1000, size=(9, 9))
    np.fill_diagonal(m, 0)
    p = problem(m.tolist())
    assert (nearest_neighbor(p, hepsi(p), arac(p)).stops
            == nearest_neighbor(p, hepsi(p), arac(p)).stops)


# --- naive ---

def test_naive_gelis_sirasini_korur():
    p = line(DOGRU)
    assert naive(p, [3, 1, 2], arac(p)).stops == [3, 1, 2]


# --- metrikler ---

def test_metrikler_donus_bacagi_ve_servis_suresi_dahil():
    p = line(DOGRU, service_s=[60.0, 0.0, 30.0], demands=[1.0, 2.0, 3.0])
    m = route_metrics(p, Route(arac(p), [2, 3, 1]))
    assert [leg.to for leg in m.legs] == [2, 3, 1, DEPOT]
    assert m.distance_m == pytest.approx(10.0)          # 1 + 2 + 2 + 5
    assert m.travel_s == pytest.approx(1.0)              # mesafe / 10
    assert m.service_s == pytest.approx(90.0)
    assert m.duration_s == pytest.approx(91.0)
    assert m.load_kg == pytest.approx(6.0)


def test_bos_rota_metrikleri_sifir():
    p = line([1])
    m = route_metrics(p, Route(arac(p), []))
    assert m.legs == () and m.distance_m == 0.0 and m.duration_s == 0.0


# --- çözücü ---

def test_solve_nn_ve_baseline_ayni_kume_uzerinde():
    p = line(DOGRU)
    out = solve(p, Algorithm.GREEDY_NN)
    assert out.result.algorithm == "GREEDY_NN"
    assert out.baseline.algorithm == "NAIVE"
    assert sorted(out.result.routes[0].stops) == sorted(out.baseline.routes[0].stops)
    assert total_distance(p, out.result) == pytest.approx(10.0)
    assert total_distance(p, out.baseline) == pytest.approx(14.0)
    assert out.unassigned == {}


def test_solve_naive_istenirse_sonuc_baseline():
    p = line(DOGRU)
    out = solve(p, Algorithm.NAIVE)
    assert out.result.routes[0].stops == [1, 2, 3]


def test_solve_depodan_ulasilamayan_durak_dislanir():
    p = problem([
        [0, 1, 2, None],
        [1, 0, 1, None],
        [2, 1, 0, None],
        [None, None, None, 0],
    ])
    out = solve(p, Algorithm.GREEDY_NN)
    assert out.unassigned == {3: UNREACHABLE}
    assert 3 not in out.result.routes[0].stops
    assert 3 not in out.baseline.routes[0].stops


def test_solve_gidilip_donulemeyen_durak_dislanir():
    """Çıkmaz tek yön: gidilir ama depoya dönülemez -> rotaya girmez."""
    p = problem([
        [0, 1, 4],
        [1, 0, 3],
        [None, None, 0],
    ])
    assert solve(p, Algorithm.GREEDY_NN).unassigned == {2: UNREACHABLE}


def test_solve_kapasite_asilirsa_reddeder():
    p = line(DOGRU, demands=[50.0, 50.0, 0.5], capacity=100.0)
    with pytest.raises(InfeasibleError, match="kapasite"):
        solve(p, Algorithm.GREEDY_NN)


def test_solve_dislanan_durak_kapasiteye_sayilmaz():
    p = problem([
        [0, 1, None],
        [1, 0, None],
        [None, None, 0],
    ], demands=[10.0, 500.0], capacity=100.0)
    out = solve(p, Algorithm.GREEDY_NN)
    assert out.result.routes[0].stops == [1]


def test_solve_hic_ulasilabilir_durak_yoksa_reddeder():
    p = problem([
        [0, None],
        [None, 0],
    ])
    with pytest.raises(InfeasibleError, match="hiçbir"):
        solve(p, Algorithm.GREEDY_NN)


def test_solve_tutarsiz_matris_acik_hata_verir():
    """Depo üzerinden geçişli olmayan elle kurulmuş matris: sessiz rota yok."""
    p = problem([
        [0, 1, 5],
        [1, 0, None],
        [1, None, 0],
    ])
    with pytest.raises(InfeasibleError):
        solve(p, Algorithm.GREEDY_NN)


def test_solve_tek_araca_sigmayan_yuk_ikinci_araca_dagitilir():
    """Faz 3: Faz 2'de reddedilen durum artık filoya dağıtılır."""
    p = line(DOGRU, demands=[50.0, 50.0, 0.5], capacity=100.0)
    p2 = dataclasses.replace(p, vehicles=(*p.vehicles, Vehicle(id=2, capacity_kg=50)))
    out = solve(p2, Algorithm.GREEDY_NN)
    assert sorted(i for r in out.result.routes for i in r.stops) == [1, 2, 3]
    assert sum(1 for r in out.result.routes if r.stops) == 2
