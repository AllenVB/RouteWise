# python/

Python 3.12 · FastAPI · uvicorn

**Durum:** Faz 3 — CVRP: çok araç, kapasite, maliyet modeli; NAIVE · GREEDY_NN · SAVINGS ·
ROUTEWISE (savings → yerel arama → simulated annealing); tohum ve sıcak başlangıç.

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

## Servisler arası uçlar

`/internal` önekli uçları yalnızca backend çağırır; `docs/api-contract.md` içindeki
genel API'nin (frontend ↔ backend) parçası değildir.

| Uç | İş |
|---|---|
| `POST /internal/matrix` | `{nodeIds}` → mesafe (m) / süre (s) matrisi, ulaşılamaz hücre `null` |
| `POST /internal/optimize` | depo + duraklar + araç + **matris** → optimize tur **ve** naive baseline |
| `POST /internal/route-geometry` | ziyaret sırasıyla `nodeIds` → bacak bacak `[boylam, enlem]` koordinatları |

`/internal/optimize` matrisi yeniden hesaplamaz: backend cache'ten okuyup gönderir.
Hata ayrımı backend'in kataloğunu izler:

| Durum | Anlamı |
|---|---|
| 422 | Biçimsiz girdi — matriste olmayan düğüm, kare olmayan/NaN/negatif matris, tekrar eden kargo veya araç, henüz yazılmamış algoritma |
| 409 `NO_FEASIBLE_SOLUTION` | Sert kısıt karşılanamıyor — kargo en büyük araca sığmıyor, toplam yük filoyu aşıyor, hiçbir durağa yol yok |

Faz 3 alanları (hepsi isteğe bağlı; verilmezse Faz 2 davranışı):

| İstek alanı | Anlamı |
|---|---|
| `vehicles[].fuelLPer100km`, `fixedCostPerDay` | araç bazlı yakıt ve sabit gider |
| `costParams` | `fuelPricePerL`, `driverCostPerHour`, `priorityPenalty` — yoksa amaç mesafedir |
| `randomSeed` | yalnızca ROUTEWISE; verilmezse üretilir, yanıtta `randomSeed` olarak döner |
| `iterations` | SA iterasyon bütçesi (varsayılan 20.000) — süre değil, sonuç tekrarlanabilsin |
| `warmStart` | `[{vehicleId, stopIds}]` önceki uygulanan koşu; yalnızca etkilenen rotalar değişir |

Yanıtta `totalCostTry` gerçek paradır (yakıt + sürücü + sabit); `priorityPenalty` amaçtaki
ceza terimidir, para değildir; `objective` ikisinin toplamıdır.

Depoyla gidiş-dönüş yolu olmayan duraklar hata değildir: `unassigned` listesinde
`UNREACHABLE` sebebiyle döner, rotaya ve baseline'a girmez. Baseline her koşuda optimize
çözümle **aynı durak kümesi** üzerinde hesaplanır; aksi hâlde tasarruf yüzdesi anlamsızdır.

Gerçek grafla duman testi (29.10.2026, 10 rastgele düğüm, tohum 42): nearest neighbor
86,35 km, naive 100,04 km; geometri uzunluğu optimize toplamıyla birebir aynı.

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
