"""Faz 6 Analiz — senaryo üretici, eşleştirilmiş anlamlılık testleri ve uçları."""

from __future__ import annotations

import random

import pytest
from fastapi.testclient import TestClient
from scipy import stats

import app.main as main
from app.analysis.scenarios import tour_scenario
from app.analysis.significance import paired_test
from tests.test_path_compare import LAT, STEP, build, grid_graph

client = TestClient(main.app)
DEPOT = 5 * 12 + 5          # 12×12 ızgaranın ortası


class TestPairedTest:
    def test_aday_her_senaryoda_ucuzsa_anlamli(self):
        rng = random.Random(1)
        ref = [rng.uniform(100, 200) for _ in range(30)]
        cand = [r * rng.uniform(0.85, 0.99) for r in ref]

        t = paired_test(cand, ref)

        assert t.n == 30 and t.candidate_better == 30 and t.ties == 0
        assert t.significant and t.wilcoxon_p < 1e-6 and t.t_p < 1e-6
        assert t.mean_diff < 0 and -15 < t.mean_diff_pct < -1
        # Değer scipy ile birebir: yön tek yönlü, sıfırlar zsplit.
        assert t.wilcoxon_p == pytest.approx(
            stats.wilcoxon(cand, ref, zero_method="zsplit", alternative="less").pvalue)

    def test_aday_pahaliysa_anlamli_degil(self):
        ref = [100.0 + i for i in range(20)]
        cand = [r + 3 + (i % 4) for i, r in enumerate(ref)]
        t = paired_test(cand, ref)
        assert not t.significant and t.wilcoxon_p > 0.9 and t.candidate_worse == 20

    def test_hep_esitse_fark_yok(self):
        t = paired_test([10.0, 20.0, 30.0], [10.0, 20.0, 30.0])
        assert t.wilcoxon_p == 1.0 and t.t_p == 1.0 and not t.significant
        assert t.ties == 3 and "fark yok" in t.note

    def test_esitlikler_n_den_atilmaz(self):
        ref = [100.0] * 10
        cand = [100.0] * 5 + [90.0] * 5
        t = paired_test(cand, ref)
        assert t.n == 10 and t.ties == 5 and t.candidate_better == 5
        assert t.t_statistic is not None and t.note is None
        assert t.median_diff == pytest.approx(-5.0)

    def test_uzunluk_farkli_ya_da_tek_senaryo_reddedilir(self):
        with pytest.raises(ValueError):
            paired_test([1.0, 2.0], [1.0])
        with pytest.raises(ValueError):
            paired_test([1.0], [2.0])


class TestScenario:
    def test_ayni_tohum_ayni_senaryo(self, tmp_path):
        g = grid_graph(tmp_path)
        a = tour_scenario(g, DEPOT, seed=7, stop_count=8, radius_m=600)
        b = tour_scenario(g, DEPOT, seed=7, stop_count=8, radius_m=600)
        c = tour_scenario(g, DEPOT, seed=8, stop_count=8, radius_m=600)
        assert a == b
        assert [s.node_id for s in a] != [s.node_id for s in c]

    def test_noktalar_yaricap_icinde_benzersiz_depo_degil(self, tmp_path):
        g = grid_graph(tmp_path)
        stops = tour_scenario(g, DEPOT, seed=3, stop_count=10, radius_m=400)
        nodes = [s.node_id for s in stops]
        assert len(set(nodes)) == 10 and DEPOT not in nodes
        for n in nodes:
            i = g.index_of(n)
            # ızgara adımı ~85 m (boylam) / ~66 m (enlem): 400 m kabaca 5 adım
            assert abs(int(g.node_ids[i]) % 12 - DEPOT % 12) <= 5
            assert abs(int(g.node_ids[i]) // 12 - DEPOT // 12) <= 7
        assert all(2.0 <= s.demand_kg <= 8.0 and 0 <= s.priority <= 3 for s in stops)
        assert [s.id for s in stops] == list(range(1, 11))

    def test_ulasilamayan_dugum_secilmez(self, tmp_path):
        # 0-1-2 iki yönlü; 3'e yalnızca gidilir (geri yol yok); 4 kopuk.
        coords = {i: (27.0 + i * STEP, LAT) for i in range(5)}
        edges = [(1, 0, 1, 90.0, 9.0), (2, 1, 2, 90.0, 9.0)]
        g = build(tmp_path, edges, coords)
        stops = tour_scenario(g, 0, seed=1, stop_count=2, radius_m=5000)
        assert sorted(s.node_id for s in stops) == [1, 2]
        with pytest.raises(ValueError):
            tour_scenario(g, 0, seed=1, stop_count=3, radius_m=5000)


class TestApi:
    @pytest.fixture(autouse=True)
    def graf(self, tmp_path, monkeypatch):
        monkeypatch.setattr(main, "_graph", grid_graph(tmp_path))

    def body(self, **extra):
        return {
            "depotNodeId": DEPOT, "seed": 11, "stopCount": 7, "radiusM": 600,
            "vehicle": {"id": 1, "capacityKg": 500, "fuelLPer100km": 9, "fixedCostPerDay": 250},
            "costParams": {"fuelPricePerL": 44, "driverCostPerHour": 150, "priorityPenalty": 2.5},
            "maxRouteDurationS": 28800, "repeats": 2, "iterations": 1000, **extra,
        }

    def test_senaryo_uretir_ve_karsilastirir(self):
        r = client.post("/internal/experiments/tour-scenario", json=self.body())
        assert r.status_code == 200, r.text
        b = r.json()
        assert b["seed"] == 11 and len(b["stops"]) == 7
        cmp = b["comparison"]
        assert cmp["referenceIsOptimal"] is True and cmp["seeds"] == [11, 12]
        assert {x["algorithm"] for x in cmp["results"]} == {"NAIVE", "GREEDY_NN", "SAVINGS", "ROUTEWISE", "DFS_EXHAUSTIVE"}
        assert all(x["gapPct"] >= 0 for x in cmp["results"] if x["feasible"])
        # Aynı tohum aynı senaryo, aynı sonuç.
        again = client.post("/internal/experiments/tour-scenario", json=self.body()).json()
        assert again["stops"] == b["stops"]
        assert [x["solution"]["objective"] for x in again["comparison"]["results"]] == \
               [x["solution"]["objective"] for x in cmp["results"]]

    def test_kesin_arama_siniri_ustu_422(self):
        assert client.post("/internal/experiments/tour-scenario", json=self.body(stopCount=11)).status_code == 422

    def test_yaricapta_yeterli_dugum_yoksa_409(self, tmp_path, monkeypatch):
        coords = {i: (27.0 + i * STEP, LAT) for i in range(4)}
        g = build(tmp_path, [(1, 0, 1, 90.0, 9.0), (2, 1, 2, 90.0, 9.0), (3, 2, 3, 90.0, 9.0)], coords, "kisa.npz")
        monkeypatch.setattr(main, "_graph", g)
        r = client.post("/internal/experiments/tour-scenario", json=self.body(depotNodeId=0, stopCount=5))
        assert r.status_code == 409
        assert "ulaşılabilir düğüm yok" in r.json()["detail"]["message"]

    def test_bilinmeyen_depo_422(self):
        assert client.post("/internal/experiments/tour-scenario", json=self.body(depotNodeId=99999)).status_code == 422

    def test_eslestirilmis_testler(self):
        r = client.post("/internal/analysis/paired", json={"pairs": [
            {"candidate": "ROUTEWISE", "reference": "NAIVE",
             "candidateValues": [90, 95, 80, 100, 70], "referenceValues": [100, 120, 90, 130, 85]},
            {"candidate": "ROUTEWISE", "reference": "DFS_EXHAUSTIVE",
             "candidateValues": [90, 95, 80], "referenceValues": [90, 95, 80]},
        ]})
        assert r.status_code == 200, r.text
        a, b = r.json()["results"]
        assert a["candidateBetter"] == 5 and a["wilcoxonP"] < 0.05 and a["significant"] is True
        assert b["ties"] == 3 and b["wilcoxonP"] == 1.0 and b["note"]

    def test_uzunluk_uyusmazligi_422(self):
        r = client.post("/internal/analysis/paired", json={"pairs": [
            {"candidate": "A", "reference": "B", "candidateValues": [1, 2, 3], "referenceValues": [1, 2]}]})
        assert r.status_code == 422
