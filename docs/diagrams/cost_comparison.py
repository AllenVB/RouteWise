"""
RouteWise — algoritma maliyet karşılaştırma figürü.

Bu betik, Python analytics modülünün üreteceği rapor figürünün prototipidir.
Çıktı vektöreldir (SVG), böylece tez raporuna ve sunuma kayıpsız gömülür.

ÖNEMLİ: Buradaki sayılar ÖRNEKTİR — figürün yapısını göstermek içindir.
Gerçek değerler optimization_runs tablosundan okunacaktır.

Çalıştırma:  python docs/diagrams/cost_comparison.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# ── Doğrulanmış kategorik palet ────────────────────────────────────────────
# dataviz validate_palette.js ile sınandı: lightness, chroma, CVD ayrımı ve
# normal görme eşiği --pairs all altında geçer. Kontrast uyarısı, her segmentin
# doğrudan etiketlenmesi ve sayfadaki tablo ile karşılanır.
C_FUEL = "#d4892a"    # yakıt maliyeti
C_DRIVER = "#3b6fb5"  # sürücü maliyeti
C_FIXED = "#9c4a86"   # araç sabit maliyeti
C_MUTED = "#94a3b8"
INK = "#1b2430"
INK_2 = "#475569"
INK_3 = "#64748b"
GRID = "#e2e8f0"
SURFACE = "#ffffff"

plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "svg.fonttype": "path",   # yazı tipi gömülü olmasın, yol olarak çizilsin
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
})

# ── ÖRNEK VERİ ─────────────────────────────────────────────────────────────
# Tek bir dağıtım senaryosu, aynı kargo listesi ve aynı filo ile koşturulmuş.
# Birim maliyetler: yakıt 44 TL/L · sürücü 150 TL/saat · araç 250 TL/gün
algos = ["Naive\n(giriş sırası)", "Greedy NN", "Clarke-Wright\nsavings", "RouteWise"]
fuel = np.array([576.0, 480.0, 440.0, 418.0])    # TL
driver = np.array([870.0, 735.0, 675.0, 630.0])  # TL
fixed = np.array([750.0, 750.0, 750.0, 500.0])   # TL  (3 / 3 / 3 / 2 araç)
vehicles = [3, 3, 3, 2]

total = fuel + driver + fixed
baseline = total[0]
savings_pct = (baseline - total) / baseline * 100.0

fig = plt.figure(figsize=(10.2, 4.5))
gs = fig.add_gridspec(1, 2, width_ratios=[1.62, 1.0], wspace=0.32,
                      left=0.112, right=0.985, top=0.80, bottom=0.215)

# ── Panel A — maliyet kırılımı (yığılmış yatay çubuk) ──────────────────────
ax = fig.add_subplot(gs[0, 0])
y = np.arange(len(algos))[::-1]  # en üstte Naive
h = 0.52

segs = [
    (fuel, C_FUEL, "Yakıt"),
    (driver, C_DRIVER, "Sürücü"),
    (fixed, C_FIXED, "Araç sabit"),
]

left = np.zeros(len(algos))
for values, color, label in segs:
    ax.barh(y, values, height=h, left=left, color=color, label=label,
            edgecolor=SURFACE, linewidth=1.6, zorder=3)
    # Doğrudan etiket — kontrast uyarısının gerektirdiği görünür etiket
    for yi, (v, l) in enumerate(zip(values, left)):
        if v >= 300:
            ax.text(l + v / 2, y[yi], f"{v:,.0f}".replace(",", "."),
                    ha="center", va="center", fontsize=8.2, color=SURFACE,
                    fontweight="bold", zorder=4)
    left = left + values

# Toplam, çubuğun ucunda
for yi, (t, n) in enumerate(zip(total, vehicles)):
    ax.text(t + 34, y[yi], f"{t:,.0f} TL".replace(",", "."), ha="left", va="center",
            fontsize=9.6, fontweight="bold", color=INK, zorder=4)
    ax.text(t + 34, y[yi] - 0.30, f"{n} araç", ha="left", va="center",
            fontsize=7.8, color=INK_3, zorder=4)

ax.set_yticks(y)
ax.set_yticklabels(algos, fontsize=9, color=INK_2)
# RouteWise satırını vurgula — konuma göre değil, metne göre eşleştir
for lbl in ax.get_yticklabels():
    if "RouteWise" in lbl.get_text():
        lbl.set_color(INK)
        lbl.set_fontweight("bold")
ax.set_xlim(0, max(total) * 1.22)
ax.set_xlabel("Toplam dağıtım maliyeti (TL)", fontsize=9, color=INK_2, labelpad=7)
ax.tick_params(axis="x", labelsize=8.4, colors=INK_3, length=0)
ax.tick_params(axis="y", length=0)
ax.xaxis.grid(True, color=GRID, linewidth=0.9, zorder=0)
ax.set_axisbelow(True)
for s in ("top", "right", "left"):
    ax.spines[s].set_visible(False)
ax.spines["bottom"].set_color(GRID)

ax.set_title("Maliyet kırılımı — algoritma başına", fontsize=11, fontweight="bold",
             color=INK, loc="left", pad=10)
# Açıklama kutusu eksenin dışında: çubuk uçlarındaki "n araç" notlarını örtmesin
handles, labels = ax.get_legend_handles_labels()
leg = fig.legend(handles, labels, loc="lower left", bbox_to_anchor=(0.112, 0.012),
                 ncol=3, frameon=False, fontsize=8.8, handlelength=1.1,
                 handleheight=0.9, columnspacing=1.4)
for t in leg.get_texts():
    t.set_color(INK_2)

# ── Panel B — tasarruf yüzdesi ─────────────────────────────────────────────
ax2 = fig.add_subplot(gs[0, 1])
labels_b = ["Greedy NN", "Clarke-Wright\nsavings", "RouteWise"]
vals_b = savings_pct[1:]
y2 = np.arange(len(labels_b))[::-1]
colors_b = [C_MUTED, C_MUTED, C_DRIVER]

ax2.barh(y2, vals_b, height=0.52, color=colors_b, zorder=3)
for yi, v in enumerate(vals_b):
    ax2.text(v + 0.7, y2[yi], f"%{v:.1f}", ha="left", va="center",
             fontsize=10, fontweight="bold",
             color=INK if yi == len(vals_b) - 1 else INK_2, zorder=4)

# Sıfır çizgisi naive taban çizgisidir; eksen başlığı bunu zaten söylüyor.
ax2.axvline(0, color=INK_3, linewidth=1.2, zorder=4)

ax2.set_yticks(y2)
ax2.set_yticklabels(labels_b, fontsize=9, color=INK_2)
for lbl in ax2.get_yticklabels():
    if "RouteWise" in lbl.get_text():
        lbl.set_color(INK)
        lbl.set_fontweight("bold")
ax2.set_xlim(0, max(vals_b) * 1.30)
ax2.set_xlabel("Naive'e göre maliyet tasarrufu (%)", fontsize=9, color=INK_2, labelpad=7)
ax2.tick_params(axis="x", labelsize=8.4, colors=INK_3, length=0)
ax2.tick_params(axis="y", length=0)
ax2.xaxis.grid(True, color=GRID, linewidth=0.9, zorder=0)
ax2.set_axisbelow(True)
for s in ("top", "right", "left"):
    ax2.spines[s].set_visible(False)
ax2.spines["bottom"].set_color(GRID)
ax2.set_title("Tasarruf oranı", fontsize=11, fontweight="bold", color=INK,
              loc="left", pad=10)

# ── Başlık ve not ──────────────────────────────────────────────────────────
fig.text(0.085, 0.945, "Algoritma maliyet karşılaştırması",
         fontsize=13.5, fontweight="bold", color=INK, ha="left")
fig.text(0.085, 0.892,
         "Aynı kargo listesi, aynı filo, tek dağıtım günü  ·  "
         "birim maliyetler: yakıt 44 TL/L · sürücü 150 TL/saat · araç 250 TL/gün",
         fontsize=8.6, color=INK_3, ha="left")
fig.text(0.985, 0.945, "ÖRNEK VERİ", fontsize=9, fontweight="bold",
         color="#b3382c", ha="right")

out = Path(__file__).with_name("cost-comparison.svg")
fig.savefig(out, format="svg", bbox_inches=None)
print(f"yazıldı: {out}")
print(f"toplam maliyetler (TL): {[round(t) for t in total]}")
print(f"tasarruf (%): {[round(s, 1) for s in savings_pct]}")
