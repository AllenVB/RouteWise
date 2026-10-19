#!/usr/bin/env python3
"""
Kendi Dijkstra çıktımızı pgRouting'inkiyle karşılaştırır.

  1) SQL üret:   python3 tools/compare_pgrouting.py sql  mine.csv  > q.sql
  2) psql ile çalıştır, çıktıyı theirs.csv'ye yaz
  3) Karşılaştır: python3 tools/compare_pgrouting.py diff mine.csv theirs.csv

Kenar SQL'i, graf export'undaki yön kuralını birebir tekrarlar: `cost`/`reverse_cost`
negatifse o yön kapalıdır; maliyet olarak `length_m` kullanılır.
"""

from __future__ import annotations

import csv
import sys

EDGES_SQL = (
    "SELECT gid AS id, source, target, "
    "CASE WHEN cost < 0 THEN -1 ELSE length_m END AS cost, "
    "CASE WHEN reverse_cost < 0 THEN -1 ELSE length_m END AS reverse_cost "
    "FROM ways"
)


def oku(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def sql(mine_path: str) -> None:
    rows = oku(mine_path)
    ciftler = ",".join(f"({r['source']},{r['target']})" for r in rows)
    print("\\pset format unaligned")
    print("\\pset fieldsep ','")
    print("\\pset footer off")
    print(
        "SELECT start_vid, end_vid, round(agg_cost::numeric, 4) AS length_m\n"
        f"FROM pgr_dijkstraCost(\n  $q${EDGES_SQL}$q$,\n"
        f"  $c$SELECT * FROM (VALUES {ciftler}) AS t(source, target)$c$,\n"
        "  directed := true)\nORDER BY start_vid, end_vid;"
    )


def diff(mine_path: str, theirs_path: str) -> int:
    mine = {(r["source"], r["target"]): r for r in oku(mine_path)}
    theirs = {}
    for r in oku(theirs_path):
        theirs[(r["start_vid"], r["end_vid"])] = float(r["length_m"])

    EPS = 0.01  # 1 cm — yuvarlama payı
    uyusan = sapan = bizde_yok = onlarda_yok = ikisi_de_yok = 0
    satirlar = []

    for key, m in mine.items():
        bulundu = m["found"] == "1"
        if key not in theirs:
            # pgRouting yol bulamadıysa satır döndürmez.
            if bulundu:
                onlarda_yok += 1
                satirlar.append(f"  {key[0]}->{key[1]}: biz {float(m['length_m']):.2f} m, pgRouting YOL YOK")
            else:
                # İkisi de ulaşılamaz diyor: bu da bir mutabakattır, sayılmalı.
                ikisi_de_yok += 1
            continue
        if not bulundu:
            bizde_yok += 1
            satirlar.append(f"  {key[0]}->{key[1]}: biz YOL YOK, pgRouting {theirs[key]:.2f} m")
            continue
        fark = abs(float(m["length_m"]) - theirs[key])
        if fark <= EPS:
            uyusan += 1
        else:
            sapan += 1
            satirlar.append(
                f"  {key[0]}->{key[1]}: biz {float(m['length_m']):.2f} m, "
                f"pgRouting {theirs[key]:.2f} m, fark {fark:.2f} m")

    mutabik = uyusan + ikisi_de_yok
    print(f"karsilastirilan cift : {len(mine)}")
    print(f"MUTABIK              : {mutabik}/{len(mine)}")
    print(f"  mesafe birebir ayni: {uyusan}")
    print(f"  ikisi de yol yok   : {ikisi_de_yok}")
    print(f"sapan                : {sapan}")
    print(f"bizde yok onlarda var: {bizde_yok}")
    print(f"bizde var onlarda yok: {onlarda_yok}")
    if satirlar:
        print("\nayrinti:")
        print("\n".join(satirlar[:20]))
    return 0 if mutabik == len(mine) else 1


if __name__ == "__main__":
    komut = sys.argv[1]
    if komut == "sql":
        sql(sys.argv[2])
    elif komut == "diff":
        raise SystemExit(diff(sys.argv[2], sys.argv[3]))
    else:
        raise SystemExit("komut: sql | diff")
