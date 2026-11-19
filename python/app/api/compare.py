"""
Karşılaştırma uçları — backend tarafından çağrılır, dışarıya açılmaz.

Seviye 1: aynı kaynak-hedef çiftinde Dijkstra, A*, BFS ve DFS. Sonuç kalıcı
değildir; backend kaydeder (`path_comparison_runs`).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from ..pathfinding.base import Weight
from ..pathfinding.compare import compare_paths
from ..schemas.compare import PathAlgorithmOut, PathCompareRequest, PathCompareResponse

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
