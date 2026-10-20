"""
Matris ucu — backend tarafından çağrılır, dışarıya açılmaz.

Yol öneki `/internal`: bu uçlar servisler arası sözleşmedir, `docs/api-contract.md`
içindeki genel API'nin (frontend ↔ backend) parçası değildir.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from ..matrix.builder import build_matrix
from ..schemas.matrix import MatrixRequest, MatrixResponse

log = logging.getLogger(__name__)
router = APIRouter(prefix="/internal", tags=["servisler arası"])


@router.post("/matrix", response_model=MatrixResponse, response_model_by_alias=True)
def matrix(req: MatrixRequest):
    """Verilen düğümler arasında tam mesafe (m) ve süre (s) matrisi."""
    from ..main import get_graph

    try:
        sonuc = build_matrix(get_graph(), req.node_ids)
    except KeyError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e

    log.info("matris: %d dugum, %d ulasilamaz cift, %.0f ms",
             len(sonuc["nodeIds"]), sonuc["unreachablePairs"],
             sonuc["computationTimeMs"])
    return sonuc
