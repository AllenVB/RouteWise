"""Faz 6 — tasarruf agregasyonu, Excel ve PDF raporu."""

from __future__ import annotations

import io
import re
from datetime import date

import pytest
from fastapi.testclient import TestClient
from openpyxl import load_workbook

import app.main as main
from app.analysis.aggregate import Period, PlanRow, aggregate, total

client = TestClient(main.app)


def row(d: date, opt: float, base: float, shipments: int = 10) -> PlanRow:
    return PlanRow(plan_date=d, optimized_cost_try=opt, baseline_cost_try=base, optimized_distance_m=opt * 50,
                   baseline_distance_m=base * 50, vehicle_count=2, shipment_count=shipments)


ROWS = [
    row(date(2026, 11, 2), 800, 1000),     # pazartesi
    row(date(2026, 11, 8), 90, 100),       # pazar: aynı ISO hafta
    row(date(2026, 11, 9), 450, 500),      # sonraki pazartesi
    row(date(2026, 12, 1), 300, 400),
    row(date(2027, 1, 4), 200, 200),
]


class TestAggregate:
    def test_haftalar_pazartesi_baslar_yuzde_toplamdan(self):
        weeks = aggregate(ROWS, Period.WEEK)
        assert [b.start for b in weeks] == [date(2026, 11, 2), date(2026, 11, 9), date(2026, 11, 30), date(2027, 1, 4)]
        first = weeks[0]
        assert first.end == date(2026, 11, 8) and first.plans == 2 and first.shipments == 20
        assert first.savings_try == 210.0
        # Ağırlıklı: 210 / 1100, günlük yüzdelerin ortalaması (%20 ve %10 → %15) değil.
        assert first.savings_pct == pytest.approx(19.09, abs=0.01)

    def test_ay_ve_yil(self):
        months = aggregate(ROWS, Period.MONTH)
        assert [(b.start, b.plans) for b in months] == [(date(2026, 11, 1), 3), (date(2026, 12, 1), 1), (date(2027, 1, 1), 1)]
        assert months[-1].savings_pct == 0.0
        years = aggregate(ROWS, Period.YEAR)
        assert [(b.start.year, b.plans) for b in years] == [(2026, 4), (2027, 1)]
        assert years[0].end == date(2026, 12, 31)

    def test_toplam_ve_bos_veri(self):
        t = total(ROWS)
        assert t.plans == 5 and t.baseline_cost_try == 2200 and t.savings_try == 360
        assert t.distance_savings_pct == pytest.approx(16.36, abs=0.01)
        assert aggregate([], Period.WEEK) == [] and total([]) is None

    def test_baseline_sifirsa_yuzde_yok(self):
        assert aggregate([row(date(2026, 11, 2), 0, 0)], Period.WEEK)[0].savings_pct is None


def rows_json():
    return [{"planDate": r.plan_date.isoformat(), "optimizedCostTry": r.optimized_cost_try,
             "baselineCostTry": r.baseline_cost_try, "optimizedDistanceM": r.optimized_distance_m,
             "baselineDistanceM": r.baseline_distance_m, "vehicleCount": 2, "shipmentCount": 10} for r in ROWS]


EXPERIMENT = {
    "id": 4, "baseSeed": 2026, "scenarios": 30, "stopCount": 10,
    "algorithms": [
        {"algorithm": "NAIVE", "scenarios": 30, "medianGapPct": 17.3, "meanGapPct": 17.2, "maxGapPct": 33.2, "optimalCount": 0, "medianTimeMs": 0.01},
        {"algorithm": "ROUTEWISE", "scenarios": 30, "medianGapPct": 0, "meanGapPct": 0, "maxGapPct": 0, "optimalCount": 30, "medianTimeMs": 239},
    ],
    "tests": [{"candidate": "ROUTEWISE", "reference": "NAIVE", "n": 30, "candidateBetter": 30, "ties": 0,
               "meanDiffPct": -14.47, "wilcoxonP": 9.3e-10, "tP": 2.1e-17, "significant": True}],
    "claim": "RouteWise, 30 senaryoda kanıtlı optimumun ortalama %0,00, en kötü %0,00 içinde; 30 senaryoda optimumu buldu.",
    "gaps": {"NAIVE": [17.0, 20.5, 12.1], "ROUTEWISE": [0.0, 0.0, 0.0]},
}
PATH_STATS = [
    {"algorithm": "DIJKSTRA", "runs": 3, "medianDeviationPct": 0, "maxDeviationPct": 0, "medianNodesVisited": 6276, "medianTimeMs": 19.1},
    {"algorithm": "DFS", "runs": 3, "medianDeviationPct": 890.6, "maxDeviationPct": 6502.6, "medianNodesVisited": 4752, "medianTimeMs": 8.3},
]


def export(fmt, **extra):
    body = {"format": fmt, "period": "MONTH", "dateFrom": "2026-11-01", "dateTo": "2027-01-31",
            "generatedAt": "2026-11-21T14:00:00", "rows": rows_json(), "pathStats": PATH_STATS,
            "experiment": EXPERIMENT, **extra}
    return client.post("/internal/reports/export", json=body)


class TestApi:
    def test_tasarruf_ucu(self):
        r = client.post("/internal/analysis/savings", json={"period": "WEEK", "rows": rows_json()})
        assert r.status_code == 200, r.text
        b = r.json()
        assert b["buckets"][0]["start"] == "2026-11-02" and b["buckets"][0]["savingsTry"] == 210.0
        assert b["total"]["savingsTry"] == 360.0

    def test_bos_aralik(self):
        b = client.post("/internal/analysis/savings", json={"period": "YEAR", "rows": []}).json()
        assert b["buckets"] == [] and b["total"] is None

    def test_excel_bes_sayfa(self):
        r = export("XLSX")
        assert r.status_code == 200, r.text
        assert r.headers["content-type"].startswith("application/vnd.openxmlformats")
        wb = load_workbook(io.BytesIO(r.content))
        assert wb.sheetnames == ["Özet", "Dönemsel tasarruf", "Günlük planlar", "Seviye 1 - yol bulma", "Seviye 2 - deney"]
        ozet = {row[0]: row[1] for row in wb["Özet"].iter_rows(values_only=True) if row and row[0]}
        assert ozet["Tasarruf (TL)"] == 360 and ozet["Sevk edilen plan"] == 5
        assert ozet["Deney"].startswith("RouteWise, 30 senaryoda")
        periods = list(wb["Dönemsel tasarruf"].iter_rows(min_row=2, values_only=True))
        assert len(periods) == 3 and periods[0][6] == 260.0     # kasım: 200 + 10 + 50
        daily = list(wb["Günlük planlar"].iter_rows(min_row=2, values_only=True))
        assert [d[0].date() for d in daily] == [r_.plan_date for r_ in ROWS]
        level1 = list(wb["Seviye 1 - yol bulma"].iter_rows(min_row=2, values_only=True))
        assert level1[1][:4] == ("DFS", 3, 890.6, 6502.6)
        deney = [r_ for r_ in wb["Seviye 2 - deney"].iter_rows(values_only=True) if r_ and r_[0]]
        assert any(r_[0] == "ROUTEWISE" and r_[5] == 30 for r_ in deney)
        assert any(r_[0] == "ROUTEWISE" and r_[1] == "NAIVE" and r_[8] == "evet" for r_ in deney)

    def test_pdf_uc_sayfa(self):
        r = export("PDF")
        assert r.status_code == 200, r.text
        assert r.headers["content-type"] == "application/pdf"
        assert r.content.startswith(b"%PDF")
        assert len(re.findall(rb"/Type\s*/Page[^s]", r.content)) == 3

    def test_deney_ve_veri_yoksa_tek_sayfa(self):
        r = export("PDF", rows=[], pathStats=[], experiment=None)
        assert r.status_code == 200
        assert len(re.findall(rb"/Type\s*/Page[^s]", r.content)) == 1
        wb = load_workbook(io.BytesIO(export("XLSX", rows=[], pathStats=[], experiment=None).content))
        assert wb["Seviye 2 - deney"]["A1"].value == "Tamamlanmış deney yok."

    def test_bilinmeyen_bicim_422(self):
        assert export("CSV").status_code == 422


class TestTurkishNumbers:
    def test_binlik_nokta_ondalik_virgul(self):
        from app.analysis.export import pct, tr
        assert tr(1398.18) == "1.398,18"
        assert tr(1234567.5, 1) == "1.234.567,5"
        assert tr(0.0) == "0,00"
        assert pct(24.8) == "%24,8"
        assert pct(-14.47, 2) == "-%14,47"
        assert pct(None) == "—"
