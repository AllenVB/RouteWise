"""
Optimizasyon ucu — backend tarafından çağrılır, dışarıya açılmaz.

Çok araç, kapasite, maliyet modeli. Optimize çözüm ve naive baseline her koşuda
birlikte döner; tasarruf yüzdesini backend hesaplar ve kalıcılaştırır.

Hata ayrımı backend'in hata kataloğunu izler: biçimsiz girdi 422 (pydantic),
sert kısıtla karşılanamayan girdi 409 `NO_FEASIBLE_SOLUTION`.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from ..schemas.optimize import (
    OptimizeRequest,
    OptimizeResponse,
    RouteOut,
    SolutionOut,
    StopOut,
    UnassignedOut,
)
from ..vrp.model import Problem, Route, Solution
from ..vrp.objective import route_cost, route_metrics
from ..vrp.solve import InfeasibleError, solve

log = logging.getLogger(__name__)
router = APIRouter(prefix="/internal", tags=["servisler arası"])


@router.post("/optimize", response_model=OptimizeResponse, response_model_by_alias=True)
def optimize(req: OptimizeRequest) -> OptimizeResponse:
    """Araçlara dağıtılmış durak sırası + aynı kümede naive baseline."""
    problem = req.to_problem()
    try:
        outcome = solve(problem, req.algorithm, seed=req.random_seed,
                        anneal=req.anneal_params(), warm_start=req.warm_routes())
    except InfeasibleError as e:
        raise HTTPException(
            status_code=409,
            detail={"code": "NO_FEASIBLE_SOLUTION", "message": str(e)},
        ) from e

    result = _solution(problem, outcome.result)
    baseline = _solution(problem, outcome.baseline)
    log.info("optimize: %d durak, %s %.0f m / naive %.0f m, %d disarida, %.1f ms",
             problem.size, result.algorithm, result.total_distance_m,
             baseline.total_distance_m, len(outcome.unassigned),
             outcome.result.computation_time_ms)

    return OptimizeResponse(
        random_seed=outcome.seed,
        result=result,
        baseline=baseline,
        unassigned=[UnassignedOut(stop_id=problem.stop(i).id, reason=sebep)
                    for i, sebep in sorted(outcome.unassigned.items())],
    )


def _solution(problem: Problem, sol: Solution) -> SolutionOut:
    routes = [_route(problem, r) for r in sol.routes]
    costs = [route_cost(problem, r.vehicle, r.stops) for r in sol.routes]
    priced = problem.costs is not None
    total_distance = sum(c.distance_m for c in costs)
    return SolutionOut(
        algorithm=sol.algorithm,
        routes=routes,
        total_distance_m=round(sum(r.total_distance_m for r in routes), 2),
        total_duration_s=round(sum(r.total_duration_s for r in routes), 1),
        vehicle_count=sum(1 for r in routes if r.stops),
        computation_time_ms=round(sol.computation_time_ms, 3),
        total_fuel_l=round(sum(c.fuel_l for c in costs), 3),
        total_cost_try=round(sum(c.money_try for c in costs), 2) if priced else None,
        priority_penalty=round(sum(c.penalty for c in costs), 2) if priced else None,
        objective=round(sum(c.objective for c in costs) if priced else total_distance, 2),
    )


def _route(problem: Problem, route: Route) -> RouteOut:
    m = route_metrics(problem, route)
    c = route_cost(problem, route.vehicle, route.stops)
    # legs[k] k. durağa varış bacağıdır; son bacak depoya dönüş.
    donus = m.legs[-1] if m.legs else None
    return RouteOut(
        vehicle_id=route.vehicle.id,
        stops=[
            StopOut(
                stop_id=problem.stop(i).id,
                node_id=problem.stop(i).node_id,
                sequence_no=k,
                distance_from_prev_m=round(leg.distance_m, 2),
                duration_from_prev_s=round(leg.duration_s, 1),
            )
            for k, (i, leg) in enumerate(zip(route.stops, m.legs, strict=False), start=1)
        ],
        return_distance_m=round(donus.distance_m, 2) if donus else 0.0,
        return_duration_s=round(donus.duration_s, 1) if donus else 0.0,
        total_distance_m=round(m.distance_m, 2),
        travel_time_s=round(m.travel_s, 1),
        service_time_s=round(m.service_s, 1),
        total_duration_s=round(m.duration_s, 1),
        total_load_kg=round(m.load_kg, 2),
        fuel_l=round(c.fuel_l, 3),
        cost_try=round(c.money_try, 2) if problem.costs is not None else None,
    )
