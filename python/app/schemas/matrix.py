"""Matris uçlarının istek/yanıt modelleri."""

from __future__ import annotations

from pydantic import BaseModel, Field


class MatrixRequest(BaseModel):
    node_ids: list[int] = Field(
        ..., alias="nodeIds", min_length=1,
        description="Yol grafındaki düğüm kimlikleri (depo ve teslimat noktaları).")

    model_config = {"populate_by_name": True}


class MatrixResponse(BaseModel):
    node_set_hash: str = Field(..., alias="nodeSetHash")
    node_ids: list[int] = Field(..., alias="nodeIds")
    distance_m: list[list[float | None]] = Field(..., alias="distanceM")
    duration_s: list[list[float | None]] = Field(..., alias="durationS")
    unreachable_pairs: int = Field(..., alias="unreachablePairs")
    computation_time_ms: float = Field(..., alias="computationTimeMs")

    model_config = {"populate_by_name": True}
