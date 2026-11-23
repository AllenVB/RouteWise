"""
Tasarruf agregasyonu — haftalık / aylık / yıllık (roadmap Faz 6, görev 8).

Girdi: sevk edilmiş her planın bir satırı — uygulanan koşunun ve aynı kargo
kümesindeki naive baseline'ın TL maliyeti ve mesafesi (backend okur ve gönderir;
Python veritabanına erişmez).

Dönem yüzdesi **ağırlıklıdır**: (Σ baseline − Σ optimize) / Σ baseline. Günlük
yüzdelerin ortalaması, küçük bir günün %40'ı ile büyük bir günün %5'ini eşit
sayardı; para olarak tasarruf toplamdan hesaplanır.

Haftalar pazartesi başlar (ISO), aylar ve yıllar takvime göredir; dönem etiketi
başlangıç günüdür.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import Enum

import pandas as pd


class Period(str, Enum):
    WEEK = "WEEK"
    MONTH = "MONTH"
    YEAR = "YEAR"

    @property
    def pandas(self) -> str:
        return {"WEEK": "W-SUN", "MONTH": "M", "YEAR": "Y"}[self.value]


@dataclass(frozen=True)
class PlanRow:
    plan_date: date
    optimized_cost_try: float
    baseline_cost_try: float
    optimized_distance_m: float
    baseline_distance_m: float
    vehicle_count: int
    shipment_count: int


@dataclass(frozen=True)
class Bucket:
    start: date
    end: date
    plans: int
    shipments: int
    optimized_cost_try: float
    baseline_cost_try: float
    savings_try: float
    savings_pct: float | None
    optimized_distance_m: float
    baseline_distance_m: float
    distance_savings_pct: float | None


def frame(rows: list[PlanRow]) -> pd.DataFrame:
    df = pd.DataFrame([r.__dict__ for r in rows],
                      columns=[f for f in PlanRow.__dataclass_fields__])
    df["plan_date"] = pd.to_datetime(df["plan_date"])
    return df


def aggregate(rows: list[PlanRow], period: Period) -> list[Bucket]:
    """Boş dönemler satır üretmez: plan yapılmamış hafta "%0 tasarruf" değildir, veri yoktur."""
    if not rows:
        return []
    df = frame(rows)
    df["period"] = df["plan_date"].dt.to_period(period.pandas)
    g = df.groupby("period", sort=True).agg(
        plans=("plan_date", "size"),
        shipments=("shipment_count", "sum"),
        optimized_cost_try=("optimized_cost_try", "sum"),
        baseline_cost_try=("baseline_cost_try", "sum"),
        optimized_distance_m=("optimized_distance_m", "sum"),
        baseline_distance_m=("baseline_distance_m", "sum"),
    )
    out: list[Bucket] = []
    for p, r in g.iterrows():
        out.append(_bucket(p.start_time.date(), p.end_time.date(), r))
    return out


def total(rows: list[PlanRow]) -> Bucket | None:
    if not rows:
        return None
    df = frame(rows)
    r = df.agg({"optimized_cost_try": "sum", "baseline_cost_try": "sum", "optimized_distance_m": "sum",
                "baseline_distance_m": "sum", "shipment_count": "sum"})
    r["plans"] = len(df)
    r["shipments"] = r["shipment_count"]
    return _bucket(df["plan_date"].min().date(), df["plan_date"].max().date(), r)


def _bucket(start: date, end: date, r) -> Bucket:
    base, opt = float(r["baseline_cost_try"]), float(r["optimized_cost_try"])
    bd, od = float(r["baseline_distance_m"]), float(r["optimized_distance_m"])
    return Bucket(
        start=start, end=end, plans=int(r["plans"]), shipments=int(r["shipments"]),
        optimized_cost_try=round(opt, 2), baseline_cost_try=round(base, 2),
        savings_try=round(base - opt, 2),
        savings_pct=None if base == 0 else round((base - opt) / base * 100.0, 2),
        optimized_distance_m=round(od, 1), baseline_distance_m=round(bd, 1),
        distance_savings_pct=None if bd == 0 else round((bd - od) / bd * 100.0, 2),
    )
