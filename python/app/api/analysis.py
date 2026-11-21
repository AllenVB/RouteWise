"""
Deney ve analiz uçları — backend tarafından çağrılır, dışarıya açılmaz.

`/internal/experiments/tour-scenario`: tohumdan bir senaryo üretir (gerçek yol
grafı) ve tüm tur algoritmalarını üzerinde koşturur. Backend 30+ senaryoyu tek
tek ister; her çağrı birkaç saniyedir, okuma zaman aşımına takılmaz.

`/internal/analysis/paired`: senaryolarda eşleştirilmiş anlamlılık testleri.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from ..analysis.scenarios import tour_scenario
from ..analysis.significance import paired_test
from ..matrix.builder import build_matrix
from ..schemas.analysis import (
    PairedOut,
    PairedRequest,
    PairedResponse,
    TourScenarioRequest,
    TourScenarioResponse,
)
from ..schemas.optimize import StopIn
from ..vrp.compare import compare_tours
from ..vrp.metaheuristic.simulated_annealing import AnnealParams
from ..vrp.model import CostParams, InfeasibleError, Problem, Stop, Vehicle
from .compare import tour_response

log = logging.getLogger(__name__)
router = APIRouter(prefix="/internal", tags=["servisler arası"])


@router.post("/experiments/tour-scenario", response_model=TourScenarioResponse, response_model_by_alias=True)
def experiment_tour_scenario(req: TourScenarioRequest) -> TourScenarioResponse:
    from ..main import get_graph

    graph = get_graph()
    try:
        stops = tour_scenario(graph, req.depot_node_id, req.seed, req.stop_count, req.radius_m)
    except KeyError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    except ValueError as e:
        raise HTTPException(status_code=409, detail={"code": "NO_FEASIBLE_SOLUTION", "message": str(e)}) from e

    m = build_matrix(graph, [req.depot_node_id, *(s.node_id for s in stops)])
    v, c = req.vehicle, req.cost_params
    problem = Problem.from_node_matrix(
        node_ids=m["nodeIds"], distance_m=m["distanceM"], duration_s=m["durationS"],
        depot_node_id=req.depot_node_id,
        stops=[Stop(id=s.id, node_id=s.node_id, demand_kg=s.demand_kg, service_time_s=s.service_time_s,
                    priority=s.priority) for s in stops],
        vehicles=[Vehicle(id=v.id, capacity_kg=v.capacity_kg, fuel_l_per_100km=v.fuel_l_per_100km,
                          fixed_cost_per_day=v.fixed_cost_per_day)],
        costs=CostParams(fuel_price_per_l=c.fuel_price_per_l, driver_cost_per_hour=c.driver_cost_per_hour,
                         priority_penalty=c.priority_penalty),
        max_duration_s=req.max_route_duration_s,
    )
    try:
        # Senaryonun tohumu SA tohumlarının da tabanıdır: deneyin tamamı tek sayıdan üretilir.
        cmp = compare_tours(problem, req.repeats, req.seed, AnnealParams(iterations=req.iterations))
    except InfeasibleError as e:
        raise HTTPException(status_code=409, detail={"code": "NO_FEASIBLE_SOLUTION", "message": str(e)}) from e

    return TourScenarioResponse(
        seed=req.seed,
        stops=[StopIn(id=s.id, node_id=s.node_id, demand_kg=s.demand_kg, service_time_s=s.service_time_s,
                      priority=s.priority) for s in stops],
        comparison=tour_response(problem, cmp, req.repeats),
    )


@router.post("/analysis/paired", response_model=PairedResponse, response_model_by_alias=True)
def analysis_paired(req: PairedRequest) -> PairedResponse:
    results = []
    for p in req.pairs:
        t = paired_test(p.candidate_values, p.reference_values, req.alpha)
        results.append(PairedOut(candidate=p.candidate, reference=p.reference, **t.__dict__))
    return PairedResponse(alpha=req.alpha, results=results)
