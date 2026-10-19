# python/

Python 3.12 · FastAPI · uvicorn

**Durum: boş — Faz 1a'da kurulacak.**

İş verisi açısından **durumsuz** servis: teslimat, araç ve rota verisi her zaman
istekle gelir, veritabanına erişilmez. Tek istisna yol grafıdır — değişmeyen
referans veri olarak açılışta `graphdata` volume'ündeki `.npz` dosyasından
yüklenir.

```
app/
  main.py              uygulama · açılışta graf yükleme
  schemas/             pydantic istek/yanıt modelleri
  graph/               model.py · loader.py
  pathfinding/         dijkstra · astar · bfs · dfs
  vrp/
    model.py objective.py feasibility.py
    construction/      savings · nearest_neighbor · naive
    localsearch/       two_opt · relocate · exchange · or_opt
    metaheuristic/     simulated_annealing
    exhaustive.py routewise.py
  analytics/           aggregate · significance · figures · export
  api/                 optimize · compare · analytics · health
tests/
```

Bağımlılıklar: numpy · pandas · scipy · matplotlib · plotly · openpyxl · pytest

## Dijkstra'nın pgRouting ile doğrulanması

Kendi Dijkstra implementasyonumuz, pgRouting'in `pgr_dijkstraCost` fonksiyonuyla
aynı sonucu vermek zorundadır. Doğrulama tekrarlanabilir:

```bash
# 1) Rastgele çiftlerde kendi sonucumuz (tohum sabit)
cd infra
docker compose run --rm --no-deps -T \
  -v "$(cd .. && pwd -W)/python/tools:/srv/tools" \
  python python3 -m tools.dijkstra_pairs 100 7 > mine.csv

# 2) Aynı çiftler için pgRouting sorgusu
cd ..
python python/tools/compare_pgrouting.py sql mine.csv > q.sql
docker cp q.sql routewise-db:/tmp/q.sql
MSYS_NO_PATHCONV=1 docker exec routewise-db \
  psql -U routewise -d routewise -q -f /tmp/q.sql > theirs.csv

# 3) Karşılaştır — çıkış kodu 0 ise tam mutabakat
python python/tools/compare_pgrouting.py diff mine.csv theirs.csv
```

> `MSYS_NO_PATHCONV=1` Windows'ta zorunludur: Git Bash `/tmp/q.sql` yolunu Windows
> yoluna çevirir ve container içindeki dosya bulunamaz.

**Son sonuç (19.10.2026):** 125 çiftte (iki ayrı tohum) tam mutabakat —
sapma yok, ulaşılabilirlik kararlarında da fark yok.

Ayrıntı: [../docs/roadmap.md](../docs/roadmap.md) — Faz 1a
