"""
Anlamlılık testi — iki algoritma aynı senaryolarda eşleştirilir.

Her senaryoda iki algoritmanın amaç değeri bir çift oluşturur. İki test birlikte
raporlanır:

- **Wilcoxon işaretli sıralar** (ana test): farkların dağılımı hakkında varsayım
  yapmaz. Tur maliyeti farkları sağa çarpıktır (çoğu senaryoda küçük, birkaçında
  büyük), normallik varsayımı zayıftır.
- **Eşleştirilmiş t-testi** (destek): ortalama farkı için; literatürde en çok
  karşılaşılan karşılaştırma budur.

Tek yönlü hipotez: "aday, referanstan daha düşük maliyetli" (H1: aday < referans).
Farkların hepsi sıfırsa (iki algoritma her senaryoda aynı) test tanımsızdır;
p = 1 raporlanır ve sebebi yazılır — "fark yok" bilgisi de sonuçtur.
"""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass

from scipy import stats

ALPHA = 0.05


@dataclass
class PairedTest:
    n: int
    candidate_better: int          # adayın daha ucuz olduğu senaryo sayısı
    ties: int
    candidate_worse: int
    mean_diff: float               # aday - referans (negatif: aday ucuz)
    median_diff: float
    mean_diff_pct: float           # referansa göre ortalama göreli fark (%)
    wilcoxon_statistic: float | None
    wilcoxon_p: float
    t_statistic: float | None
    t_p: float
    significant: bool              # Wilcoxon p < alpha
    note: str | None = None


def paired_test(candidate: list[float], reference: list[float], alpha: float = ALPHA) -> PairedTest:
    if len(candidate) != len(reference):
        raise ValueError("eşleştirilmiş test için iki dizi aynı uzunlukta olmalı")
    n = len(candidate)
    if n < 2:
        raise ValueError("en az iki senaryo gerekir")
    diffs = [c - r for c, r in zip(candidate, reference)]
    rel = [(c - r) / r * 100.0 for c, r in zip(candidate, reference) if r != 0]
    better = sum(1 for x in diffs if x < -1e-9)
    worse = sum(1 for x in diffs if x > 1e-9)
    ties = n - better - worse

    note = None
    if better + worse == 0:
        w_stat, w_p, t_stat, t_p = None, 1.0, None, 1.0
        note = "İki algoritma her senaryoda aynı sonucu verdi; fark yok."
    else:
        # zsplit: eşit (sıfır farklı) senaryolar sıralamaya yarı yarıya katılır;
        # 'wilcox' onları atar ve n'yi gizlice küçültürdü.
        w = stats.wilcoxon(candidate, reference, zero_method="zsplit", alternative="less")
        w_stat, w_p = float(w.statistic), float(w.pvalue)
        if statistics.pstdev(diffs) == 0:
            # Farklar sabit: t istatistiği sonsuz, test anlamsız.
            t_stat, t_p = None, (0.0 if diffs[0] < 0 else 1.0)
            note = "Fark her senaryoda aynı; t-testi tanımsız."
        else:
            t = stats.ttest_rel(candidate, reference, alternative="less")
            t_stat, t_p = float(t.statistic), float(t.pvalue)

    return PairedTest(
        n=n,
        candidate_better=better,
        ties=ties,
        candidate_worse=worse,
        mean_diff=statistics.fmean(diffs),
        median_diff=statistics.median(diffs),
        mean_diff_pct=statistics.fmean(rel) if rel else 0.0,
        wilcoxon_statistic=w_stat,
        wilcoxon_p=_clean(w_p),
        t_statistic=t_stat,
        t_p=_clean(t_p),
        significant=_clean(w_p) < alpha,
        note=note,
    )


def _clean(p: float) -> float:
    return 1.0 if p is None or math.isnan(p) else p
