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

Ayrıntı: [../docs/roadmap.md](../docs/roadmap.md) — Faz 1a
