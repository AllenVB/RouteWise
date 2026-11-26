"""
Rapor dışa aktarma — Excel (5 sayfa) ve PDF (roadmap Faz 6, görev 11).

Excel sayfaları:
  1. Özet               dönem, toplam tasarruf, deneyin iddia cümlesi
  2. Dönemsel tasarruf  haftalık / aylık / yıllık kova
  3. Günlük planlar     sevk edilmiş her plan: optimize ve naive maliyet, mesafe
  4. Seviye 1           yol bulma algoritmaları: sapma, gezilen düğüm, süre
  5. Seviye 2 deney     algoritma başına sapma istatistiği ve anlamlılık testleri

PDF aynı içeriğin okunabilir hâlidir: her bölüm bir sayfa, figür + tablo.
Sayılar yalnızca girdiden gelir; rapor hiçbir değeri kendisi üretmez.
"""

from __future__ import annotations

import io
from dataclasses import dataclass, field
from datetime import date, datetime

import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

from . import figures
from .aggregate import Bucket, Period, PlanRow, aggregate, total

HEADER_FILL = PatternFill("solid", fgColor="E2E8F0")
BOLD = Font(bold=True)


@dataclass
class ExperimentReport:
    id: int
    base_seed: int
    scenarios: int
    stop_count: int
    algorithms: list[dict]          # algorithm, scenarios, median/mean/max gap, optimal count, median time
    tests: list[dict]               # candidate, reference, n, wilcoxon p, t p, mean diff pct, significant
    claim: str
    gaps: dict[str, list[float]] = field(default_factory=dict)


@dataclass
class ReportData:
    period: Period
    date_from: date
    date_to: date
    generated_at: datetime
    rows: list[PlanRow]
    path_stats: list[dict]          # algorithm, runs, median_deviation_pct, max_deviation_pct, median_nodes_visited, median_time_ms
    experiment: ExperimentReport | None

    @property
    def buckets(self) -> list[Bucket]:
        return aggregate(self.rows, self.period)

    @property
    def total(self) -> Bucket | None:
        return total(self.rows)


# --- Excel -------------------------------------------------------------------------

def to_xlsx(d: ReportData) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "Özet"
    t = d.total
    _rows(ws, [
        ["RouteWise — tasarruf ve algoritma raporu"],
        ["Aralık", f"{d.date_from:%d.%m.%Y} – {d.date_to:%d.%m.%Y}"],
        ["Oluşturulma", f"{d.generated_at:%d.%m.%Y %H:%M}"],
        [],
        ["Sevk edilen plan", t.plans if t else 0],
        ["Teslimat", t.shipments if t else 0],
        ["Naive maliyet (TL)", t.baseline_cost_try if t else 0],
        ["Uygulanan maliyet (TL)", t.optimized_cost_try if t else 0],
        ["Tasarruf (TL)", t.savings_try if t else 0],
        ["Tasarruf (%)", t.savings_pct if t else None],
        ["Mesafe tasarrufu (%)", t.distance_savings_pct if t else None],
        [],
        ["Deney", d.experiment.claim if d.experiment else "Tamamlanmış deney yok."],
    ])
    ws["A1"].font = Font(bold=True, size=14)
    ws.column_dimensions["A"].width = 26
    ws.column_dimensions["B"].width = 100
    ws["B13"].alignment = Alignment(wrap_text=True, vertical="top")

    ws = wb.create_sheet("Dönemsel tasarruf")
    _table(ws, ["Başlangıç", "Bitiş", "Plan", "Teslimat", "Naive (TL)", "Uygulanan (TL)", "Tasarruf (TL)",
                "Tasarruf (%)", "Naive (km)", "Uygulanan (km)", "Mesafe tasarrufu (%)"],
           [[b.start, b.end, b.plans, b.shipments, b.baseline_cost_try, b.optimized_cost_try, b.savings_try,
             b.savings_pct, round(b.baseline_distance_m / 1000, 2), round(b.optimized_distance_m / 1000, 2),
             b.distance_savings_pct] for b in d.buckets])

    ws = wb.create_sheet("Günlük planlar")
    _table(ws, ["Tarih", "Teslimat", "Araç", "Naive (TL)", "Uygulanan (TL)", "Tasarruf (TL)", "Naive (km)",
                "Uygulanan (km)"],
           [[r.plan_date, r.shipment_count, r.vehicle_count, r.baseline_cost_try, r.optimized_cost_try,
             round(r.baseline_cost_try - r.optimized_cost_try, 2), round(r.baseline_distance_m / 1000, 2),
             round(r.optimized_distance_m / 1000, 2)] for r in sorted(d.rows, key=lambda x: x.plan_date)])

    ws = wb.create_sheet("Seviye 1 - yol bulma")
    _table(ws, ["Algoritma", "Koşu", "Sapma medyan (%)", "Sapma en kötü (%)", "Gezilen düğüm medyan",
                "Süre medyan (ms)"],
           [[s["algorithm"], s["runs"], s["median_deviation_pct"], s["max_deviation_pct"],
             s["median_nodes_visited"], s["median_time_ms"]] for s in d.path_stats])

    ws = wb.create_sheet("Seviye 2 - deney")
    e = d.experiment
    if e is None:
        ws.append(["Tamamlanmış deney yok."])
    else:
        _rows(ws, [[f"Deney {e.id} · {e.scenarios} senaryo · {e.stop_count} durak · tohum {e.base_seed}"], [e.claim], []])
        ws["A1"].font = BOLD
        start = ws.max_row + 1
        _table(ws, ["Algoritma", "Senaryo", "Sapma medyan (%)", "Sapma ort. (%)", "Sapma en kötü (%)",
                    "Optimumu bulduğu", "Süre medyan (ms)"],
               [[a["algorithm"], a["scenarios"], a["median_gap_pct"], a["mean_gap_pct"], a["max_gap_pct"],
                 a["optimal_count"], a["median_time_ms"]] for a in e.algorithms], first_row=start)
        ws.append([])
        _table(ws, ["Aday", "Referans", "n", "Aday daha ucuz", "Eşit", "Ort. fark (%)", "Wilcoxon p", "t p",
                    "Anlamlı (α = 0,05)"],
               [[t_["candidate"], t_["reference"], t_["n"], t_["candidate_better"], t_["ties"],
                 round(t_["mean_diff_pct"], 3), t_["wilcoxon_p"], t_["t_p"], "evet" if t_["significant"] else "hayır"]
                for t_ in e.tests], first_row=ws.max_row + 1)

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _rows(ws, rows: list[list]) -> None:
    for r in rows:
        ws.append(r)


def _table(ws, header: list[str], rows: list[list], first_row: int = 1) -> None:
    for c, h in enumerate(header, start=1):
        cell = ws.cell(row=first_row, column=c, value=h)
        cell.font = BOLD
        cell.fill = HEADER_FILL
    for i, r in enumerate(rows, start=first_row + 1):
        for c, v in enumerate(r, start=1):
            ws.cell(row=i, column=c, value=v)
    for c, h in enumerate(header, start=1):
        letter = get_column_letter(c)
        ws.column_dimensions[letter].width = max(ws.column_dimensions[letter].width or 0, len(h) + 4)
    ws.freeze_panes = ws.cell(row=first_row + 1, column=1) if first_row == 1 else ws.freeze_panes


# --- PDF ---------------------------------------------------------------------------

def to_pdf(d: ReportData) -> bytes:
    buf = io.BytesIO()
    with PdfPages(buf, metadata={"Title": "RouteWise raporu", "Creator": "RouteWise"}) as pdf:
        t = d.total
        fig = figures.savings_figure(d.buckets, d.period)
        fig.set_size_inches(8.27, 11.69)          # A4 dikey
        fig.subplots_adjust(top=0.6, bottom=0.08)
        lines = [
            "RouteWise — tasarruf ve algoritma raporu",
            f"Aralık: {d.date_from:%d.%m.%Y} – {d.date_to:%d.%m.%Y}   ·   Oluşturulma: {d.generated_at:%d.%m.%Y %H:%M}",
            "",
        ]
        if t:
            lines += [
                f"Sevk edilen plan: {t.plans}   ·   Teslimat: {t.shipments}",
                f"Naive maliyet: {tr(t.baseline_cost_try)} TL   ·   Uygulanan: {tr(t.optimized_cost_try)} TL",
                f"Tasarruf: {tr(t.savings_try)} TL"
                + ("" if t.savings_pct is None else f" ({pct(t.savings_pct)})")
                + ("" if t.distance_savings_pct is None else f"   ·   Mesafe: {pct(t.distance_savings_pct)}"),
            ]
        else:
            lines.append("Bu aralıkta sevk edilmiş plan yok.")
        if d.experiment:
            lines += ["", _wrap(d.experiment.claim, 95)]
        fig.text(0.07, 0.95, lines[0], fontsize=15, weight="bold", va="top")
        fig.text(0.07, 0.91, "\n".join(lines[1:]), fontsize=10, va="top", linespacing=1.6)
        pdf.savefig(fig)
        plt.close(fig)

        if d.path_stats:
            fig = figures.path_figure(d.path_stats)
            fig.set_size_inches(8.27, 11.69)
            fig.subplots_adjust(top=0.72, bottom=0.08)
            fig.text(0.07, 0.95, "Seviye 1 — yol bulma (Dijkstra referans)", fontsize=14, weight="bold", va="top")
            fig.text(0.07, 0.90, _table_text(
                ["Algoritma", "Koşu", "Sapma med.", "Sapma en kötü", "Düğüm med.", "Süre med."],
                [[s["algorithm"], s["runs"], pct(s["median_deviation_pct"]), pct(s["max_deviation_pct"]),
                  tr(s["median_nodes_visited"], 0), f"{tr(s['median_time_ms'], 1)} ms"] for s in d.path_stats]),
                fontsize=9, va="top", family="DejaVu Sans Mono")
            pdf.savefig(fig)
            plt.close(fig)

        if d.experiment:
            e = d.experiment
            fig = figures.experiment_figure(e.gaps)
            fig.set_size_inches(8.27, 11.69)
            fig.subplots_adjust(top=0.6, bottom=0.08)
            fig.text(0.07, 0.95, f"Seviye 2 — deney {e.id} ({e.scenarios} senaryo, {e.stop_count} durak, tohum {e.base_seed})",
                     fontsize=13, weight="bold", va="top")
            fig.text(0.07, 0.91, _table_text(
                ["Algoritma", "Sapma med.", "Ort.", "En kötü", "Optimum"],
                [[a["algorithm"], pct(a["median_gap_pct"], 2), pct(a["mean_gap_pct"], 2),
                  pct(a["max_gap_pct"], 2), f"{a['optimal_count']}/{a['scenarios']}"] for a in e.algorithms])
                + "\n\n" + _table_text(
                ["Referans", "n", "Ucuz", "Eşit", "Ort. fark", "Wilcoxon p", "t p"],
                [[t_["reference"], t_["n"], t_["candidate_better"], t_["ties"], pct(t_["mean_diff_pct"], 2),
                  _p(t_["wilcoxon_p"]), _p(t_["t_p"])] for t_ in e.tests]),
                fontsize=9, va="top", family="DejaVu Sans Mono")
            pdf.savefig(fig)
            plt.close(fig)
    return buf.getvalue()


def _table_text(header: list[str], rows: list[list]) -> str:
    cells = [header] + [[str(c) for c in r] for r in rows]
    widths = [max(len(r[i]) for r in cells) for i in range(len(header))]
    fmt = "  ".join(f"{{:<{w}}}" for w in widths)
    lines = [fmt.format(*header), fmt.format(*("─" * w for w in widths))]
    lines += [fmt.format(*r) for r in cells[1:]]
    return "\n".join(lines)


def _wrap(text: str, width: int) -> str:
    out, line = [], ""
    for word in text.split():
        if len(line) + len(word) + 1 > width:
            out.append(line)
            line = word
        else:
            line = f"{line} {word}".strip()
    out.append(line)
    return "\n".join(out)


_TR_DIGITS = str.maketrans(",.", ".,")


def tr(v: float, digits: int = 2) -> str:
    """Türkçe sayı: binlik nokta, ondalık virgül (1.398,18)."""
    return f"{v:,.{digits}f}".translate(_TR_DIGITS)


def pct(v: float | None, digits: int = 1) -> str:
    """Türkçe yüzde: işaret önde (-%14,5)."""
    if v is None:
        return "—"
    return ("-" if v < 0 else "") + "%" + tr(abs(v), digits)


def _p(v: float) -> str:
    return "< 0,0001" if v < 0.0001 else f"{v:.4f}".replace(".", ",")
