"""
Karşılaştırma uçları — backend tarafından çağrılır, dışarıya açılmaz.

Seviye 1: aynı kaynak-hedef çiftinde Dijkstra, A*, BFS ve DFS.
Seviye 2: tek aracın tur sırası — NAIVE, GREEDY_NN, SAVINGS, ROUTEWISE ve
n ≤ 10 ise DFS_EXHAUSTIVE (kesin optimum). Sonuçlar kalıcı değildir; backend kaydeder.
"""

from __future__ import annotations

import logging
import random

from fastapi import APIRouter, HTTPException

from ..pathfinding.base import Weight
from ..pathfinding.compare import compare_paths
from ..schemas.compare import (
    PathAlgorithmOut,
    PathCompareRequest,
    PathCompareResponse,
    TourAlgorithmOut,
    TourCompareRequest,
    TourCompareResponse,
)
from ..schemas.optimize import UnassignedOut
from ..vrp.compare import compare_tours
from ..vrp.model import InfeasibleError
from .optimize import _solution

log = logging.getLogger(__name__)
router = APIRouter(prefix="/internal/compare", tags=["servisler arası"])


@router.post("/path", response_model=PathCompareResponse, response_model_by_alias=True)
def compare_path(req: PathCompareRequest) -> PathCompareResponse:
    """Dört algoritma, aynı girdi; Dijkstra'ya göre sapma ve gezilen düğüm."""
    from ..main import get_graph

    graph = get_graph()
    weight = Weight(req.weight)
    try:
        runs = compare_paths(graph, req.from_node_id, req.to_node_id, weight, req.repeats)
    except KeyError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    results = []
    for run in runs:
        r = run.result
        idx = [graph.index_of(n) for n in r.path]
        results.append(PathAlgorithmOut(
            algorithm=run.algorithm,
            found=r.found,
            length_m=round(r.length_m, 2),
            duration_s=round(r.duration_s, 1),
            edge_count=max(0, len(r.path) - 1),
            nodes_visited=r.nodes_visited,
            computation_time_ms=round(run.median_ms, 3),
            deviation_pct=None if run.deviation_pct is None else round(run.deviation_pct, 2),
            same_path_as_reference=run.same_path_as_reference,
            path=r.path,
            coordinates=[(round(float(graph.node_lon[i]), 6), round(float(graph.node_lat[i]), 6)) for i in idx],
        ))
    log.info("seviye 1: %s -> %s (%s, %dx): %s", req.from_node_id, req.to_node_id, req.weight, req.repeats,
             ", ".join(f"{x.algorithm} {x.nodes_visited} dugum {x.deviation_pct}%" for x in results))
    return PathCompareResponse(from_node_id=req.from_node_id, to_node_id=req.to_node_id,
                               weight=req.weight, repeats=req.repeats, results=results)


@router.post("/tour", response_model=TourCompareResponse, response_model_by_alias=True)
def compare_tour(req: TourCompareRequest) -> TourCompareResponse:
    """Aynı durak kümesi, tek araç; sapma kesin optimuma (yoksa en iyi bulunana) göre."""
    problem = req.to_problem()
    seed = req.random_seed if req.random_seed is not None else random.SystemRandom().randrange(2**31 - 100)
    try:
        cmp = compare_tours(problem, req.repeats, seed, req.anneal_params())
    except InfeasibleError as e:
        raise HTTPException(status_code=409, detail={"code": "NO_FEASIBLE_SOLUTION", "message": str(e)}) from e

    results = [
        TourAlgorithmOut(
            algorithm=r.algorithm,
            feasible=r.solution is not None,
            error=r.error,
            solution=None if r.solution is None else _solution(problem, r.solution),
            computation_time_ms=round(r.median_ms, 3),
            gap_pct=None if r.gap_pct is None else round(r.gap_pct, 3),
            random_seed=r.seed,
            objective_min=None if r.objective_min is None else round(r.objective_min, 2),
            objective_max=None if r.objective_max is None else round(r.objective_max, 2),
            explored=r.explored,
        )
        for r in cmp.runs
    ]
    log.info("seviye 2: %d durak, %dx, referans %s (%s): %s", problem.size, req.repeats, cmp.reference,
             "optimum" if cmp.reference_is_optimal else "en iyi",
             ", ".join(f"{x.algorithm} %{x.gap_pct}" for x in results))
    return TourCompareResponse(
        repeats=req.repeats,
        seeds=cmp.seeds,
        reference_algorithm=cmp.reference,
        reference_is_optimal=cmp.reference_is_optimal,
        exhaustive_skipped=cmp.exhaustive_skipped,
        results=results,
        unassigned=[UnassignedOut(stop_id=problem.stop(i).id, reason=why) for i, why in sorted(cmp.unassigned.items())],
    )
