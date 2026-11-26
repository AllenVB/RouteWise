"""
Seviye 2 — kesin tur (DFS_EXHAUSTIVE) ve tur karşılaştırması.

Kesin arama, küçük örneklerde tüm permütasyonları deneyen kaba kuvvetle
karşılaştırılır: dal-sınır kesmesi hiçbir zaman optimumu atmamalı.
"""

from __future__ import annotations

import dataclasses
import itertools
import math
import random
import time

import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.vrp.compare import EXHAUSTIVE, compare_tours
from app.vrp.exhaustive import MAX_STOPS, exhaustive_tour
from app.vrp.metaheuristic.simulated_annealing import AnnealParams
from app.vrp.model import CostParams, InfeasibleError, Stop, Vehicle
from app.vrp.objective import route_objective, route_duration
from tests.vrp_fixtures import problem

client = TestClient(main.app)
FAST = AnnealParams(iterations=2_000)


def scattered(n, seed, *, priced=True, workday_s=None, service_s=60.0):
    """Düzlemde rastgele n durak (Öklid matrisi, üçgen eşitsizliği sağlanır), öncelikli ve fiyatlı."""
    rng = random.Random(seed)
    pts = [(0.0, 0.0)] + [(rng.uniform(-3000, 3000), rng.uniform(-3000, 3000)) for _ in range(n)]
    dist = [[math.dist(a, b) for b in pts] for a in pts]
    p = problem(dist, service_s=[service_s] * n)
    stops = tuple(dataclasses.replace(s, priority=rng.randint(0, 5)) for s in p.stops)
    return dataclasses.replace(
        p,
        stops=stops,
        vehicles=(Vehicle(id=1, capacity_kg=100.0, fuel_l_per_100km=9.0, fixed_cost_per_day=250.0),),
        costs=CostParams(fuel_price_per_l=44.0, driver_cost_per_hour=150.0, priority_penalty=2.5) if priced else None,
        max_duration_s=workday_s,
    )


def brute_force(p):
    v = p.vehicles[0]
    best = None
    for perm in itertools.permutations(range(1, p.size + 1)):
        if p.max_duration_s is not None and route_duration(p, perm) > p.max_duration_s + 1e-6:
            continue
        c = route_objective(p, v, perm)
        if best is None or c < best - 1e-9:
            best = c
    return best


class TestExhaustive:
    @pytest.mark.parametrize("seed", range(8))
    def test_kaba_kuvvetle_ayni_optimum(self, seed):
        p = scattered(7, seed)
        tour = exhaustive_tour(p, p.vehicles[0], range(1, 8))
        assert tour.objective == pytest.approx(brute_force(p))
        assert sorted(tour.stops) == list(range(1, 8))

    @pytest.mark.parametrize("seed", range(4))
    def test_maliyet_modeli_yokken_en_kisa_tur(self, seed):
        p = scattered(6, seed, priced=False)
        assert exhaustive_tour(p, p.vehicles[0], range(1, 7)).objective == pytest.approx(brute_force(p))

    @pytest.mark.parametrize("seed", range(4))
    def test_calisma_gunu_kisitiyla_optimum(self, seed):
        p = scattered(7, seed)
        free = exhaustive_tour(p, p.vehicles[0], range(1, 8))
        # Sınır optimumun süresinin biraz altında: optimum değişmek zorunda (ya da tur kalmaz).
        tight = dataclasses.replace(p, max_duration_s=route_duration(p, free.stops) * 1.02)
        expected = brute_force(tight)
        tour = exhaustive_tour(tight, tight.vehicles[0], range(1, 8))
        assert tour.objective == pytest.approx(expected)
        assert route_duration(tight, tour.stops) <= tight.max_duration_s + 1e-6

    def test_hicbir_sira_sigmiyorsa_olursuz(self):
        p = scattered(5, 1, workday_s=60.0)
        with pytest.raises(InfeasibleError):
            exhaustive_tour(p, p.vehicles[0], range(1, 6))

    def test_kapasite_asilirsa_olursuz(self):
        p = dataclasses.replace(scattered(4, 2), vehicles=(Vehicle(id=1, capacity_kg=2.0),))
        with pytest.raises(InfeasibleError):
            exhaustive_tour(p, p.vehicles[0], range(1, 5))

    def test_sinirin_ustu_reddedilir(self):
        p = scattered(MAX_STOPS + 1, 3)
        with pytest.raises(ValueError):
            exhaustive_tour(p, p.vehicles[0], range(1, MAX_STOPS + 2))

    def test_baslangic_cozumu_optimumsa_o_doner_ve_kesme_guclenir(self):
        p = scattered(8, 4)
        cold = exhaustive_tour(p, p.vehicles[0], range(1, 9))
        warm = exhaustive_tour(p, p.vehicles[0], range(1, 9), incumbent=cold.stops)
        assert warm.stops == cold.stops
        assert warm.explored < cold.explored

    def test_on_durak_makul_surede(self):
        p = scattered(10, 5)
        t0 = time.perf_counter()
        exhaustive_tour(p, p.vehicles[0], range(1, 11))
        assert time.perf_counter() - t0 < 30.0


class TestCompareTours:
    def test_sapma_kesin_optimuma_gore(self):
        p = scattered(8, 6)
        cmp = compare_tours(p, repeats=3, seed=10, anneal=FAST)

        by = {r.algorithm: r for r in cmp.runs}
        assert list(by) == ["NAIVE", "GREEDY_NN", "SAVINGS", "ROUTEWISE", EXHAUSTIVE]
        assert cmp.reference == EXHAUSTIVE and cmp.reference_is_optimal
        assert by[EXHAUSTIVE].gap_pct == 0.0
        assert all(r.gap_pct >= -1e-9 for r in cmp.runs)     # hiçbir sezgisel optimumu geçemez
        assert by["NAIVE"].gap_pct > 0
        assert cmp.seeds == [10, 11, 12]
        assert by["ROUTEWISE"].seed in cmp.seeds
        assert by["ROUTEWISE"].objective_min <= by["ROUTEWISE"].objective_max
        assert by[EXHAUSTIVE].explored > 0

    def test_on_durak_ustunde_kesin_arama_atlanir(self):
        p = scattered(MAX_STOPS + 2, 7)
        cmp = compare_tours(p, repeats=1, seed=1, anneal=FAST)
        assert EXHAUSTIVE not in {r.algorithm for r in cmp.runs}
        assert not cmp.reference_is_optimal
        assert "12 durak" in cmp.exhaustive_skipped
        assert min(r.gap_pct for r in cmp.runs) == 0.0

    def test_ulasilamayan_durak_hepsinden_ayni_sekilde_cikar(self):
        dist = [[0, 100, None], [100, 0, None], [None, None, 0]]
        cmp = compare_tours(problem(dist), repeats=1, seed=0, anneal=FAST)
        assert cmp.unassigned == {2: "UNREACHABLE"}
        for r in cmp.runs:
            assert [i for route in r.solution.routes for i in route.stops] == [1]

    def test_tek_arac_disi_reddedilir(self):
        p = scattered(3, 8)
        two = dataclasses.replace(p, vehicles=(*p.vehicles, Vehicle(id=2, capacity_kg=10.0)))
        with pytest.raises(ValueError):
            compare_tours(two)


class TestTourApi:
    def body(self, **extra):
        node_ids = [999, 1000, 1001, 1002]
        pts = {999: (0, 0), 1000: (0, 1000), 1001: (1000, 1000), 1002: (1000, 0)}
        dist = [[math.dist(pts[a], pts[b]) for b in node_ids] for a in node_ids]
        return {
            "depotNodeId": 999,
            # Geliş sırası zikzak: naive 1002 -> 1000 -> 1001 gezer.
            "stops": [{"id": 52, "nodeId": 1002, "demandKg": 1}, {"id": 50, "nodeId": 1000, "demandKg": 1},
                      {"id": 51, "nodeId": 1001, "demandKg": 1}],
            "vehicles": [{"id": 7, "capacityKg": 100, "fuelLPer100km": 9, "fixedCostPerDay": 250}],
            "matrix": {"nodeIds": node_ids, "distanceM": dist, "durationS": [[d / 10 for d in r] for r in dist]},
            "costParams": {"fuelPricePerL": 44, "driverCostPerHour": 150},
            "randomSeed": 5, "iterations": 1000, "repeats": 2, **extra,
        }

    def test_tum_algoritmalar_ve_kesin_optimum(self):
        r = client.post("/internal/compare/tour", json=self.body())
        assert r.status_code == 200, r.text
        b = r.json()
        assert b["referenceAlgorithm"] == "DFS_EXHAUSTIVE" and b["referenceIsOptimal"] is True
        assert b["seeds"] == [5, 6]
        by = {x["algorithm"]: x for x in b["results"]}
        assert by["DFS_EXHAUSTIVE"]["gapPct"] == 0.0
        assert by["NAIVE"]["gapPct"] > 0
        assert by["DFS_EXHAUSTIVE"]["solution"]["totalDistanceM"] == 4000.0
        assert [s["stopId"] for s in by["NAIVE"]["solution"]["routes"][0]["stops"]] == [52, 50, 51]
        assert by["ROUTEWISE"]["randomSeed"] in (5, 6)
        assert by["NAIVE"]["randomSeed"] is None

    def test_iki_arac_422(self):
        body = self.body()
        body["vehicles"].append({"id": 8, "capacityKg": 100})
        assert client.post("/internal/compare/tour", json=body).status_code == 422

    def test_tek_araca_sigmayan_yuk_409(self):
        body = self.body()
        body["vehicles"][0]["capacityKg"] = 2
        r = client.post("/internal/compare/tour", json=body)
        assert r.status_code == 409
        assert r.json()["detail"]["code"] == "NO_FEASIBLE_SOLUTION"

    def test_bilinmeyen_alan_422(self):
        assert client.post("/internal/compare/tour", json=self.body(algorithm="NAIVE")).status_code == 422
