"""
Rapor figürleri (roadmap Faz 6, görev 10; prototip `docs/diagrams/cost_comparison.py`).

Her fonksiyon bir matplotlib Figure döner; PDF raporu bunları sayfa sayfa yazar,
tez için ayrıca SVG olarak kaydedilebilir. Renkler prototipteki doğrulanmış
paletten; her değer çubuğun üstünde yazılıdır (renge güvenilmez).
"""

from __future__ import annotations

from collections.abc import Sequence

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.figure import Figure  # noqa: E402

from .aggregate import Bucket, Period  # noqa: E402

C_BASE = "#94a3b8"     # naive baseline
C_OPT = "#3b6fb5"      # RouteWise / uygulanan koşu
C_ACCENT = "#d4892a"
INK = "#1b2430"
INK_2 = "#475569"
GRID = "#e2e8f0"

plt.rcParams.update({
    "font.family": "DejaVu Sans",      # Türkçe karakterleri kapsar
    "svg.fonttype": "path",
    "figure.facecolor": "#ffffff",
    "axes.facecolor": "#ffffff",
    "axes.edgecolor": GRID,
    "axes.labelcolor": INK_2,
    "xtick.color": INK_2,
    "ytick.color": INK_2,
    "axes.spines.top": False,
    "axes.spines.right": False,
})

PERIOD_TITLE = {Period.WEEK: "Haftalık", Period.MONTH: "Aylık", Period.YEAR: "Yıllık"}


def _label(b: Bucket, period: Period) -> str:
    if period is Period.WEEK:
        return b.start.strftime("%d.%m")
    if period is Period.MONTH:
        return b.start.strftime("%m.%Y")
    return str(b.start.year)


def savings_figure(buckets: Sequence[Bucket], period: Period) -> Figure:
    fig, ax = plt.subplots(figsize=(9, 4.2))
    if not buckets:
        ax.text(0.5, 0.5, "Bu aralıkta sevk edilmiş plan yok.", ha="center", va="center", color=INK_2)
        ax.set_axis_off()
        return fig
    xs = range(len(buckets))
    w = 0.38
    ax.bar([x - w / 2 for x in xs], [b.baseline_cost_try for b in buckets], w, color=C_BASE, label="Geliş sırası (naive)")
    ax.bar([x + w / 2 for x in xs], [b.optimized_cost_try for b in buckets], w, color=C_OPT, label="Uygulanan rota")
    top = max(b.baseline_cost_try for b in buckets)
    for x, b in zip(xs, buckets):
        if b.savings_pct is not None:
            ax.text(x, max(b.baseline_cost_try, b.optimized_cost_try) + top * 0.02,
                    ("-" if b.savings_pct < 0 else "") + "%" + f"{abs(b.savings_pct):.1f}".replace(".", ","),
                    ha="center", va="bottom", fontsize=9, color=INK)
    ax.set_xticks(list(xs), [_label(b, period) for b in buckets], rotation=0 if len(buckets) < 10 else 45)
    ax.set_ylabel("Maliyet (TL)")
    ax.set_ylim(0, top * 1.15)
    ax.grid(axis="y", color=GRID)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, loc="upper left")
    ax.set_title(f"{PERIOD_TITLE[period]} maliyet ve tasarruf", loc="left", color=INK)
    fig.tight_layout()
    return fig


def path_figure(stats: Sequence[dict]) -> Figure:
    """stats: algorithm, medianNodesVisited, medianDeviationPct (Seviye 1 ortalaması)."""
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(9, 3.6))
    names = [s["algorithm"] for s in stats]
    # DFS'in binlerce yüzdelik sapması BFS'in birkaç yüzdesini görünmez kılar: sapma ekseni
    # 1'in altında doğrusal, üstünde logaritmik (symlog). Değerler çubuğun üstünde yazılı.
    a2.set_yscale("symlog", linthresh=1)
    for ax, key, title, fmt in ((a1, "median_nodes_visited", "Gezilen düğüm (medyan)", "{:.0f}"),
                                (a2, "median_deviation_pct", "Dijkstra'dan sapma (medyan, %, log)", "{:.1f}")):
        vals = [s[key] for s in stats]
        bars = ax.bar(names, vals, color=[C_OPT if n in ("DIJKSTRA", "ASTAR") else C_ACCENT for n in names])
        for b, v in zip(bars, vals):
            ax.text(b.get_x() + b.get_width() / 2, b.get_height(), fmt.format(v).replace(".", ","), ha="center", va="bottom",
                    fontsize=9, color=INK)
        ax.set_title(title, loc="left", color=INK, fontsize=11)
        ax.grid(axis="y", color=GRID)
        ax.set_axisbelow(True)
    fig.tight_layout()
    return fig


def experiment_figure(gaps: dict[str, list[float]]) -> Figure:
    """Senaryo başına optimumdan sapma; kutu grafiği dağılımı, nokta her senaryoyu gösterir."""
    fig, ax = plt.subplots(figsize=(9, 3.8))
    names = [n for n in gaps if gaps[n]]
    if not names:
        ax.text(0.5, 0.5, "Deney verisi yok.", ha="center", va="center", color=INK_2)
        ax.set_axis_off()
        return fig
    ax.boxplot([gaps[n] for n in names], tick_labels=names, showfliers=False,
               medianprops={"color": INK}, boxprops={"color": INK_2}, whiskerprops={"color": INK_2},
               capprops={"color": INK_2})
    for i, n in enumerate(names, start=1):
        ax.scatter([i] * len(gaps[n]), gaps[n], s=10, alpha=0.5, color=C_OPT, zorder=3)
    ax.set_ylabel("Optimumdan sapma (%)")
    ax.grid(axis="y", color=GRID)
    ax.set_axisbelow(True)
    ax.set_title("Seviye 2 — senaryo başına optimumdan sapma", loc="left", color=INK)
    fig.tight_layout()
    return fig
