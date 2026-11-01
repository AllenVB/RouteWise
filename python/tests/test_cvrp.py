"""
Faz 3 — CVRP: maliyet modeli, kurucular, RouteWise.

Roadmap DoD maddeleri:
  - aynı tohumla aynı girdi aynı rotayı verir (determinizm)
  - sıcak başlangıç: tek paket eklenince rota baştan aşağı değişmez
  - naive baseline her koşuda hesaplanır
  - küçük örnekte (n ≤ 8) sonuç, tam aramayla bulunan optimumun makul yakınında
"""

from __future__ import annotations

import itertools
import math

import numpy as np
import pytest

from app.vrp.construction.naive import naive_fleet
from app.vrp.construction.nearest_neighbor import nearest_neighbor_fleet
from app.vrp.construction.savings import savings
from app.vrp.feasibility import violations
from app.vrp.localsearch.state import RouteSet
from app.vrp.metaheuristic.simulated_annealing import AnnealParams
from app.vrp.model import CostParams, InfeasibleError, Problem, Stop, Vehicle
from app.vrp.objective import arrival_offsets, route_cost, route_duration, route_objective
from app.vrp.routewise import routewise
from app.vrp.solve import EXCEEDS_WORKDAY, Algorithm, solve

COSTS = CostParams(fuel_price_per_l=44.0, driver_cost_per_hour=150.0, priority_penalty=25.0)
FAST = AnnealParams(iterations=3000)


def euclid(seed: int, n: int, vehicles: list[float], *, demand=(5.0, 40.0), costs=COSTS,
           priorities: bool = True) -> Problem:
    """Depo merkezde, duraklar 10×10 km karede; 40 km/sa; 5 dk servis."""
    rng = np.random.default_rng(seed)
    pts = np.vstack([[5000.0, 5000.0], rng.uniform(0, 10_000, size=(n, 2))])
    d = np.linalg.norm(pts[:, None, :] - pts[None, :, :], axis=2)
    dem = rng.uniform(*demand, size=n)
    return Problem(
        depot_node_id=0,
        stops=tuple(Stop(id=500 + i, node_id=i + 1, demand_kg=float(dem[i]), service_time_s=300.0,
                         priority=int(rng.integers(0, 4)) if priorities else 0) for i in range(n)),
        vehicles=tuple(Vehicle(id=k + 1, capacity_kg=c, fuel_l_per_100km=9.5, fixed_cost_per_day=250.0)
                       for k, c in enumerate(vehicles)),
        distance=d,
        duration=d / (40_000 / 3600),
        costs=costs,
    )


def stops_of(p: Problem) -> list[int]:
    return list(range(1, p.size + 1))


def feasible(p: Problem, routes: list[list[int]]) -> bool:
    return violations(p, RouteSet(p, routes).to_solution("T"), stops_of(p)) == []


def objective(p: Problem, routes: list[list[int]]) -> float:
    return RouteSet(p, routes).total


# --- maliyet modeli ---

class TestCostModel:

    def test_route_cost_breakdown(self):
        # depo -> 1 (10 km, 1 sa) -> depo (10 km, 1 sa); 1 öncelikli, 30 dk servis
        d = np.array([[0.0, 10_000.0], [10_000.0, 0.0]])
        p = Problem(0, (Stop(1, 1, 5.0, service_time_s=1800.0, priority=2),),
                    (Vehicle(1, 100.0, fuel_l_per_100km=10.0, fixed_cost_per_day=250.0),),
                    d, d / 10_000 * 3600, costs=COSTS)

        c = route_cost(p, p.vehicles[0], [1])

        assert c.distance_m == pytest.approx(20_000)
        assert c.duration_s == pytest.approx(2.5 * 3600)          # 2 sa yol + 0,5 sa servis
        assert c.fuel_l == pytest.approx(2.0)                      # 20 km × 10 L/100 km
        assert c.fuel_try == pytest.approx(88.0)                   # × 44 TL
        assert c.driver_try == pytest.approx(375.0)                # 2,5 sa × 150 TL
        assert c.fixed_try == pytest.approx(250.0)
        assert c.penalty == pytest.approx(25.0 * 2 * 1.0)          # katsayı × öncelik × varış (1 sa)
        assert c.money_try == pytest.approx(713.0)
        assert c.objective == pytest.approx(763.0)

    @pytest.mark.parametrize("seed", range(5))
    def test_fast_objective_equals_cost_breakdown(self, seed):
        """route_objective, route_cost'un yalın kopyasıdır; ikisi ayrışırsa algoritma yanlış şeyi küçültür."""
        p = euclid(seed, 12, [500.0])
        r = list(np.random.default_rng(seed).permutation(range(1, 13)))
        assert route_objective(p, p.vehicles[0], r) == pytest.approx(route_cost(p, p.vehicles[0], r).objective)
        bare = euclid(seed, 12, [500.0], costs=None)
        assert route_objective(bare, bare.vehicles[0], r) == pytest.approx(route_cost(bare, bare.vehicles[0], r).distance_m)

    def test_empty_route_costs_nothing_not_even_fixed(self):
        p = euclid(1, 3, [100.0])
        assert route_objective(p, p.vehicles[0], []) == 0.0

    def test_without_cost_params_objective_is_distance(self):
        p = euclid(1, 4, [500.0], costs=None)
        r = [1, 2, 3, 4]
        assert route_objective(p, p.vehicles[0], r) == pytest.approx(route_cost(p, p.vehicles[0], r).distance_m)

    def test_priority_pulls_urgent_stop_forward(self):
        """
        Doğru üzerinde, depo ortada: 1@-1 km, 2@+2 km (öncelik 10). İki sıra da 6 km;
        yalnızca acil durağa varış zamanı farklı (2000 s'ye karşı 4000 s).
        """
        xs = [0.0, -1000.0, 2000.0]
        d = np.array([[abs(a - b) for b in xs] for a in xs])
        stops = (Stop(1, 1, 1.0, priority=0), Stop(2, 2, 1.0, priority=10))
        v = (Vehicle(1, 100.0, fuel_l_per_100km=9.5),)
        cheap = Problem(0, stops, v, d, d, costs=CostParams(44.0, 150.0, priority_penalty=0.0))
        urgent = Problem(0, stops, v, d, d, costs=CostParams(44.0, 150.0, priority_penalty=100_000.0))

        assert route_objective(cheap, v[0], [1, 2]) == pytest.approx(route_objective(cheap, v[0], [2, 1]))
        assert route_objective(urgent, v[0], [2, 1]) < route_objective(urgent, v[0], [1, 2])


# --- kurucular ---

@pytest.mark.parametrize("seed", range(6))
@pytest.mark.parametrize("build", [naive_fleet, nearest_neighbor_fleet, savings])
def test_constructors_are_feasible_with_tight_heterogeneous_fleet(build, seed):
    # Toplam yük ~ 22 × 22,5 = ~500 kg; filo 600 kg, araçlar farklı.
    p = euclid(seed, 22, [300.0, 180.0, 120.0])
    routes = build(p, stops_of(p))
    assert feasible(p, routes)


def test_savings_merges_into_one_route_when_capacity_allows():
    p = euclid(2, 8, [10_000.0, 10_000.0])
    routes = savings(p, stops_of(p))
    assert sum(1 for r in routes if r) == 1


def test_naive_is_first_fit_in_arrival_order():
    p = euclid(3, 5, [50.0, 1000.0], demand=(20.0, 20.0))
    routes = naive_fleet(p, stops_of(p))
    assert routes == [[1, 2], [3, 4, 5]]


# --- RouteWise ---

@pytest.mark.parametrize("seed", range(5))
def test_routewise_feasible_and_not_worse_than_its_starting_point(seed):
    p = euclid(seed, 25, [300.0, 200.0, 150.0])
    start = savings(p, stops_of(p))

    rs = routewise(p, stops_of(p), seed=seed, params=FAST)

    assert feasible(p, rs.routes)
    assert rs.total <= objective(p, start) + 1e-6


def test_same_seed_same_routes():
    p = euclid(11, 20, [250.0, 250.0])
    a = routewise(p, stops_of(p), seed=42, params=FAST).routes
    b = routewise(p, stops_of(p), seed=42, params=FAST).routes
    assert a == b


def test_solve_returns_generated_seed_and_reproduces_with_it():
    p = euclid(12, 15, [300.0, 200.0])
    first = solve(p, Algorithm.ROUTEWISE, anneal=FAST)
    assert first.seed is not None

    again = solve(p, Algorithm.ROUTEWISE, seed=first.seed, anneal=FAST)

    assert [r.stops for r in again.result.routes] == [r.stops for r in first.result.routes]


def test_unseeded_algorithms_report_no_seed():
    p = euclid(12, 6, [300.0])
    assert solve(p, Algorithm.SAVINGS, seed=7).seed is None


@pytest.mark.parametrize("seed", range(4))
def test_routewise_beats_naive_on_average_problem(seed):
    p = euclid(seed, 30, [350.0, 350.0, 200.0])
    out = solve(p, Algorithm.ROUTEWISE, seed=1, anneal=FAST)
    rw = RouteSet.from_solution(p, out.result).total
    nv = RouteSet.from_solution(p, out.baseline).total
    assert rw < nv


# --- tam aramaya göre sapma (DoD: n ≤ 8) ---

def exhaustive(p: Problem) -> float:
    """İki araç için tüm (atama × sıra) kombinasyonları — yalnızca test referansı."""
    n = p.size
    best = math.inf
    rs = RouteSet(p, [[] for _ in p.vehicles])
    for perm in itertools.permutations(range(1, n + 1)):
        for cut in range(n + 1):
            routes = [list(perm[:cut]), list(perm[cut:])]
            if rs.delta(dict(enumerate(routes))) is None:
                continue
            best = min(best, objective(p, routes))
    return best


@pytest.mark.parametrize("seed", range(4))
def test_routewise_close_to_exhaustive_optimum_on_small_instances(seed):
    p = euclid(100 + seed, 7, [90.0, 90.0], demand=(10.0, 25.0))
    optimum = exhaustive(p)

    rs = routewise(p, stops_of(p), seed=seed, params=AnnealParams(iterations=4000))
    gap = (rs.total - optimum) / optimum

    assert gap >= -1e-9, "tam aramadan iyi sonuç: referans ya da amaç hatalı"
    assert gap <= 0.02, f"optimumdan sapma %{gap * 100:.2f}"


# --- sıcak başlangıç ---

def adjacency(routes: list[list[int]]) -> set[tuple[int, int]]:
    return {(a, b) for r in routes for a, b in zip([0, *r], [*r, 0], strict=True)}


@pytest.mark.parametrize("seed", range(4))
def test_warm_start_adding_one_stop_keeps_most_of_the_route(seed):
    """Yeni kargo eklenince önceki ardışık durak çiftlerinin büyük kısmı korunur."""
    full = euclid(200 + seed, 21, [400.0, 400.0])
    before_stops = list(range(1, 21))                       # 21. durak sonradan geliyor
    before = routewise(full, before_stops, seed=seed, params=FAST).routes

    cold = routewise(full, stops_of(full), seed=seed + 99, params=FAST).routes
    warm = routewise(full, stops_of(full), seed=seed, params=FAST, warm_start=before).routes

    assert feasible(full, warm)
    kept_warm = len(adjacency(before) & adjacency(warm)) / len(adjacency(before))
    assert kept_warm >= 0.8, f"sıcak başlangıç rotanın yalnızca %{kept_warm * 100:.0f}'ini korudu"
    vehicle_of = {i: k for k, r in enumerate(before) for i in r}
    moved = sum(1 for k, r in enumerate(warm) for i in r if i in vehicle_of and vehicle_of[i] != k)
    assert moved <= 2
    # Karşılaştırma için: soğuk başlangıç bu korumayı garanti etmez (yalnızca bilgi).
    assert feasible(full, cold)


def test_warm_start_drops_stops_no_longer_planned_and_survives_bad_input():
    p = euclid(7, 6, [200.0, 200.0])
    previous = [[1, 2, 99, 3], [3, 4]]          # 99 bilinmiyor, 3 iki kez
    rs = routewise(p, [1, 2, 3, 4, 5], seed=1, params=FAST, warm_start=previous)
    assert sorted(i for r in rs.routes for i in r) == [1, 2, 3, 4, 5]


# --- kapasite ön kontrolü ---

def test_stop_heavier_than_biggest_vehicle_is_rejected_with_its_id():
    p = euclid(5, 3, [10.0, 10.0], demand=(20.0, 20.0))
    with pytest.raises(InfeasibleError, match="en büyük aracın kapasitesini"):
        solve(p, Algorithm.ROUTEWISE, anneal=FAST)


def test_total_load_beyond_fleet_is_rejected():
    p = euclid(5, 6, [50.0, 50.0], demand=(20.0, 20.0))
    with pytest.raises(InfeasibleError, match="filonun toplam kapasitesi"):
        solve(p, Algorithm.SAVINGS)


# --- Faz 4: çalışma günü sınırı ---

def with_workday(p: Problem, hours: float) -> Problem:
    import dataclasses
    return dataclasses.replace(p, max_duration_s=hours * 3600)


@pytest.mark.parametrize("seed", range(5))
@pytest.mark.parametrize("build", [naive_fleet, nearest_neighbor_fleet, savings])
def test_constructors_respect_workday(build, seed):
    """
    DoD: gün sınırını aşan çözüm üretilmez — kapasite gevşek, bağlayan süre.
    Kurucu ya olurlu çözüm döner ya da açıkça reddeder; aşan rota asla.
    (Naive geliş sırasıyla zikzak çizdiği için sıkı günde reddedebilir.)
    """
    p = with_workday(euclid(seed, 30, [10_000.0, 10_000.0, 10_000.0]), hours=2.0)
    try:
        routes = build(p, stops_of(p))
    except InfeasibleError:
        assert build is naive_fleet, "akıllı kurucular bu örnekte olurlu çözüm bulmalı"
        return
    assert feasible(p, routes)
    assert sum(1 for r in routes if r) >= 2, "tek araç 2 saate sığmamalıydı; sınır gerçekten bağlıyor mu?"


@pytest.mark.parametrize("seed", range(4))
def test_routewise_respects_workday(seed):
    p = with_workday(euclid(seed, 30, [10_000.0, 10_000.0, 10_000.0]), hours=2.0)
    out = solve(p, Algorithm.ROUTEWISE, seed=1, anneal=FAST)
    for r in out.result.routes + (out.baseline.routes if out.baseline else []):
        assert route_duration(p, r.stops) <= 2.0 * 3600 + 1e-6


def test_baseline_failure_does_not_fail_optimization():
    """Naive sıkı günde kurulamasa da optimize çözüm döner; sebep raporlanır."""
    failing = [s for s in range(20)
               if _naive_fails(with_workday(euclid(s, 30, [10_000.0] * 3), hours=2.0))]
    assert failing, "testin anlamı için naive'in çöktüğü bir örnek gerekli"
    p = with_workday(euclid(failing[0], 30, [10_000.0] * 3), hours=2.0)

    out = solve(p, Algorithm.SAVINGS)

    assert out.baseline is None
    assert "sığmıyor" in (out.baseline_error or "")
    assert feasible(p, RouteSet.from_solution(p, out.result).routes)


def test_naive_failure_is_fatal_when_naive_itself_is_requested():
    p = next(q for q in (with_workday(euclid(s, 30, [10_000.0] * 3), hours=2.0) for s in range(20))
             if _naive_fails(q))
    with pytest.raises(InfeasibleError):
        solve(p, Algorithm.NAIVE)


def _naive_fails(p: Problem) -> bool:
    try:
        naive_fleet(p, stops_of(p))
    except InfeasibleError:
        return True
    return False


def test_stop_beyond_workday_alone_is_reported_not_failed():
    """Depoya gidip dönmek bile günü aşıyorsa durak dışarıda kalır, çözüm yine üretilir."""
    d = np.array([[0.0, 1000.0, 90_000.0], [1000.0, 0.0, 90_000.0], [90_000.0, 90_000.0, 0.0]])
    p = Problem(0, (Stop(1, 1, 1.0, 300.0), Stop(2, 2, 1.0, 300.0)), (Vehicle(1, 100.0),),
                d, d / 10.0, max_duration_s=3600.0)          # 2. durağa gidiş tek başına 2,5 sa
    out = solve(p, Algorithm.GREEDY_NN)
    assert out.unassigned == {2: EXCEEDS_WORKDAY}
    assert out.result.routes[0].stops == [1]


def test_fleet_short_on_time_is_an_explicit_error():
    p = with_workday(euclid(3, 30, [10_000.0]), hours=1.0)    # tek araç, 30 durak, 1 saat
    with pytest.raises(InfeasibleError, match="çalışma süresi"):
        solve(p, Algorithm.SAVINGS)


def test_arrival_offsets_include_previous_service_times():
    # depo -> 1 (100 s) [servis 300] -> 2 (50 s)
    d = np.array([[0.0, 100.0, 150.0], [100.0, 0.0, 50.0], [150.0, 50.0, 0.0]])
    p = Problem(0, (Stop(1, 1, 1.0, 300.0), Stop(2, 2, 1.0, 120.0)), (Vehicle(1, 100.0),), d, d)
    assert arrival_offsets(p, [1, 2]) == [100.0, 450.0]
    assert route_duration(p, [1, 2]) == pytest.approx(100 + 300 + 50 + 120 + 150)
