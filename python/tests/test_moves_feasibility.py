"""
Faz 3 DoD #1 — hiçbir yerel arama hamlesi olursuz çözüm üretmez.

Algoritmalardan (savings, SA) ÖNCE yazıldı. Her operatörün TÜM adayları sıkı
kapasiteli, asimetrik, kısmen yolsuz rastgele problemlerde denenir: `delta()`
kabul ettiği her hamle uygulandıktan sonra `violations()` boş olmalıdır; reddettiği
her hamle gerçekten olursuz olmalıdır.
"""

from __future__ import annotations

import random

import numpy as np
import pytest

from app.vrp.feasibility import violations
from app.vrp.localsearch import exchange, or_opt, relocate, two_opt
from app.vrp.localsearch.search import local_search
from app.vrp.localsearch.state import RouteSet
from app.vrp.model import CostParams, Problem, Stop, Vehicle
from app.vrp.objective import route_duration

OPERATORS = {
    "two_opt": two_opt,
    "relocate": relocate,
    "exchange": exchange,
    "or_opt": or_opt,
}


def random_problem(seed: int, n: int = 10, vehicles: int = 3, holes: float = 0.05,
                   workday: bool = False) -> Problem:
    """
    Sıkı kapasite: toplam yük filonun ~%85'i; birkaç tek yönlü 'yol yok' hücresi.
    `workday`: çalışma günü sınırı da sıkı — araç başına ortalama işin ~%160'ı.
    """
    rng = np.random.default_rng(seed)
    d = rng.uniform(100, 5000, size=(n + 1, n + 1))
    np.fill_diagonal(d, 0)
    mask = rng.random((n + 1, n + 1)) < holes
    mask[0, :] = mask[:, 0] = False          # depo bağlantıları açık kalsın
    np.fill_diagonal(mask, False)
    d[mask] = np.inf
    demand = rng.uniform(5, 40, size=n)
    cap = demand.sum() / vehicles / 0.85
    return Problem(
        depot_node_id=0,
        stops=tuple(Stop(id=100 + i, node_id=i + 1, demand_kg=float(demand[i]),
                         service_time_s=300.0, priority=int(rng.integers(0, 4))) for i in range(n)),
        vehicles=tuple(Vehicle(id=k + 1, capacity_kg=float(cap * (0.8 + 0.2 * k)),
                               fuel_l_per_100km=9.5, fixed_cost_per_day=250.0) for k in range(vehicles)),
        distance=d,
        duration=d / 10.0,
        costs=CostParams(fuel_price_per_l=44.0, driver_cost_per_hour=150.0, priority_penalty=25.0),
        max_duration_s=_tight_workday(d, n, vehicles) if workday else None,
    )


def _tight_workday(d: np.ndarray, n: int, vehicles: int) -> float:
    """Gidiş-dönüş + teslim ortalamasıyla kaba iş yükü; sıkı ama çözümü olan bir sınır."""
    finite = d[np.isfinite(d)]
    per_stop = float(np.median(finite)) / 10.0 + 300.0
    return per_stop * n / vehicles * 1.6


def feasible_start(p: Problem) -> list[list[int]]:
    """Kapasiteye, yollara ve çalışma gününe uyan ilk çözüm (ilk uyan araç)."""
    routes: list[list[int]] = [[] for _ in p.vehicles]
    for i in range(1, p.size + 1):
        for k, v in enumerate(p.vehicles):
            load = sum(p.demand[j] for j in routes[k]) + p.demand[i]
            cand = routes[k] + [i]
            rs_ok = load <= v.capacity_kg and all(
                np.isfinite(p.distance[a, b]) for a, b in zip([0, *cand], [*cand, 0], strict=True)) and (
                p.max_duration_s is None or route_duration(p, cand) <= p.max_duration_s)
            if rs_ok:
                routes[k] = cand
                break
        else:
            pytest.skip("bu tohumla sürülebilir başlangıç kurulamadı")
    return routes


def is_feasible(p: Problem, routes: list[list[int]]) -> bool:
    return violations(p, RouteSet(p, routes).to_solution("T"), range(1, p.size + 1)) == []


@pytest.mark.parametrize("workday", [False, True], ids=["kapasite", "kapasite+gun"])
@pytest.mark.parametrize("seed", range(8))
@pytest.mark.parametrize("name", OPERATORS)
def test_every_accepted_candidate_is_feasible_and_every_rejected_is_not(name, seed, workday):
    p = random_problem(seed, workday=workday)
    rs = RouteSet(p, feasible_start(p))
    accepted = rejected = 0
    for changes in OPERATORS[name].neighbors(rs):
        trial = [changes.get(k, r) for k, r in enumerate(rs.routes)]
        d = rs.delta(changes)
        if d is None:
            rejected += 1
            assert not is_feasible(p, trial), f"{name}: olurlu hamle reddedildi {changes}"
        else:
            accepted += 1
            assert is_feasible(p, trial), f"{name}: OLURSUZ hamle kabul edildi {changes}"
    assert accepted > 0


@pytest.mark.parametrize("workday", [False, True], ids=["kapasite", "kapasite+gun"])
@pytest.mark.parametrize("seed", range(8))
@pytest.mark.parametrize("name", OPERATORS)
def test_random_neighbors_never_break_feasibility_when_applied(name, seed, workday):
    p = random_problem(seed, workday=workday)
    rs = RouteSet(p, feasible_start(p))
    rng = random.Random(seed)
    for _ in range(300):
        ch = OPERATORS[name].random_neighbor(rs, rng)
        if ch is not None and rs.delta(ch) is not None:
            rs.apply(ch)
            assert is_feasible(p, rs.routes)


@pytest.mark.parametrize("workday", [False, True], ids=["kapasite", "kapasite+gun"])
@pytest.mark.parametrize("seed", range(8))
def test_local_search_keeps_feasibility_and_never_worsens(seed, workday):
    p = random_problem(seed, n=12, workday=workday)
    rs = RouteSet(p, feasible_start(p))
    before = rs.total

    local_search(rs)

    assert is_feasible(p, rs.routes)
    assert rs.total <= before + 1e-9


def test_delta_matches_full_recomputation():
    """Artımlı değerlendirme, çözümü baştan değerlendirmekle aynı sonucu vermeli."""
    p = random_problem(3, n=12)
    rs = RouteSet(p, feasible_start(p))
    for changes in relocate.neighbors(rs):
        d = rs.delta(changes)
        if d is None:
            continue
        trial = RouteSet(p, [changes.get(k, r) for k, r in enumerate(rs.routes)])
        assert trial.total - rs.total == pytest.approx(d)
