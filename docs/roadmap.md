# RouteWise — Geliştirme Yol Planı

> Sürüm: v1 · 11.10.2026. Mimari tamamlandıktan sonra, kod yazımına geçiş planı.
> İlgili dokümanlar: [architecture.md](architecture.md) · [RouteWise-Detayli-Sistem-Analizi.pdf](RouteWise-Detayli-Sistem-Analizi.pdf)

## 0. Bu plan nasıl okunur

Plan **fazlara** bölünmüştür. Her fazın sonunda **çalışan ve demo edilebilir** bir sistem
olur — bu pazarlık konusu değildir. Takvim sıkışırsa son biten faz sunulur.

Her faz şu başlıkları taşır:

| Başlık | Anlamı |
|---|---|
| **Amaç** | Bu faz bittiğinde sistem ne yapabiliyor olacak |
| **Görevler** | Sırayla yapılacak işler |
| **Biten iş tanımı** | Fazın bittiğini kanıtlayan ölçütler — hepsi sağlanmadan sonraki faza geçilmez |
| **Risk** | Bu fazda ne ters gidebilir, nasıl erken fark edilir |

Süreler **hafta** cinsinden verilmiştir ve kendi takvimine göre esnetilmelidir.
Toplam tahmin: **14–18 hafta**.

---

## Takvim özeti

| Faz | İş | Süre | Kümülatif |
|---|---|---|---|
| **0** | Hazırlık — Docker, OSM verisi, repo iskeleti | 1 hafta | 1 |
| **1a** | Kod iskeleti — paket düzeni, boş katmanlar, derlenen proje | 1 hafta | 2 |
| **1b** | Arayüz tasarımı — tıklanabilir prototip + PDF wireframe | 1,5 hafta | 3,5 |
| **1c** | API sözleşmesi — endpoint'ler, DTO'lar, hata biçimleri | 0,5 hafta | 4 |
| **2** | Katman 1 — kargo listesi, adres doğrulama, tek araç TSP | 2,5 hafta | 6,5 |
| **3** | Katman 2 — çok araç, kapasite, maliyet modeli (CVRP) | 3 hafta | 9,5 |
| **4** | Katman 3 — süre kısıtı, ETA, bildirim, roller | 2 hafta | 11,5 |
| **5** | Katman 4 — dinamik yeniden rotalama | 2 hafta | 13,5 |
| **6** | Katman 5 — algoritma karşılaştırma + analiz + raporlar | 2,5 hafta | 16 |
| **7** | Kapanış — test tamamlama, dokümantasyon, tez raporu | 2 hafta | 18 |

**Kritik yol:** Faz 0 → 1a → 2 → 3. Bu zincirde gecikme tüm projeyi öteler.
Faz 1b (arayüz) kısmen paralel yürütülebilir.

---

## Faz 0 — Hazırlık (1 hafta)

### Amaç
Geliştirme ortamı ayakta, Bandırma yol grafı veritabanında, `docker compose up` çalışıyor.

### Görevler

1. **Docker Desktop'ı çalışır hale getir**
   - Motor kapalıysa: yükseltilmiş PowerShell'de `Start-Service com.docker.service`, ardından Docker Desktop'ı aç
   - `docker info` çıktısı alınabilmeli
2. **Repo klasör yapısını oluştur** — `backend/`, `python/`, `frontend/`, `infra/`, `docs/`
3. **`infra/docker-compose.yml`** — `db`, `backend`, `python`, `frontend`, `dataprep` servisleri
   - `db`: `pgrouting/pgrouting:17-3.5-4.0.1`
   - Volume'ler: `pgdata`, `osmdata`, `graphdata`, `gradle-cache`
   - Healthcheck'ler: `pg_isready`, `/actuator/health`, `/health`
4. **`.env.example`** yaz, `.env` dosyasını `.gitignore`'a ekle
5. **`infra/dataprep/` imajı** — Debian + `osmium-tool` + `osm2pgrouting`
6. **Bandırma sınır poligonunu üret**
   - OSM'de Bandırma ilçe idari sınır ilişkisini bul, ilişki kimliğini not et
   - `.poly` dosyasına çevir → `infra/dataprep/bandirma.poly`
7. **Veri hattını çalıştır** — `download` → `clip` → `load` → `export`
8. **Doğrula** — `ways` ve `ways_vertices_pgr` tabloları dolu; `pgr_dijkstra` ile iki nokta arası sorgu sonuç veriyor

### Biten iş tanımı

> **Düzeltme (11.10.2026):** Bu listede önce "dört servis ayağa kalkıyor" yazıyordu.
> Yanlıştı: Faz 0'da `backend/`, `python/` ve `frontend/` boştur, Dockerfile'ları yoktur,
> dolayısıyla build edilemezler. Uygulama servisleri **Faz 1a'nın** işidir.
> Faz 0 gerçekte `db` + `dataprep` + yol grafı teslim eder.

- [ ] `docker compose up -d db` ile veritabanı ayağa kalkıyor, healthcheck yeşil
- [ ] `postgis` ve `pgrouting` uzantıları kurulu
- [ ] `ways` tablosunda Bandırma yol ağı var, satır sayısı makul (binler mertebesi)
- [ ] `pgr_dijkstra` örnek sorgusu mesafe döndürüyor
- [ ] `graphdata` volume'ünde `bandirma-graph.npz` var, bbox Bandırma'yı gösteriyor
- [ ] Kurulum adımları belgelendi — **başka biri sıfırdan kurabilmeli**

### Risk
- **OSM poligonu yanlış/eksik** → graf boş veya çok küçük çıkar. Erken fark etmek için
  `ways` satır sayısına ve haritada birkaç yola bak.
- **osm2pgrouting bellek hatası** → Bandırma ölçeğinde beklenmez; olursa poligonu daralt.
- **Docker Desktop motoru** → bu makinede bilinen sorun, Faz 0'ın ilk işi olmasının sebebi bu.

---

## Faz 1a — Kod İskeleti (1 hafta)

### Amaç
Üç proje de derleniyor/çalışıyor, paket düzeni oturmuş, boş katmanlar yerinde.
**Henüz iş mantığı yok.**

### Backend paket düzeni

Paket-özellik (package-by-feature) yaklaşımı: her özellik kendi `entity / repository /
service / controller / dto` katmanlarını içinde taşır. Bu, 15 tablolu bir projede
katman-bazlı düzenden daha okunabilirdir.

```
backend/src/main/java/com/routewise/
  RouteWiseApplication.java
  config/           SecurityConfig · JacksonConfig · OpenApiConfig · RestClientConfig
  common/
    error/          GlobalExceptionHandler · ApiError · alan bazlı istisnalar
    validation/     ortak doğrulayıcılar
    geo/            PostGIS yardımcıları, koordinat dönüşümleri
  auth/             JWT üretimi/doğrulaması · login · rol çözümleme
  user/             users tablosu
  courier/          couriers
  recipient/        recipients
  depot/            depots
  vehicle/          vehicles
  address/
    verification/   durum makinesi · geofence · yola snap · aykırı değer kontrolü
  shipment/         shipments · sipariş no üretimi
  plan/             delivery_plans
  routing/
    matrix/         DistanceMatrixProvider arayüzü · cache · Dijkstra çağrısı
    graph/          pgRouting referans sorguları
  optimization/
    client/         PythonOptimizerClient (REST)
    service/        koşu yönetimi · sonucun kalıcılaştırılması
  comparison/       karşılaştırma koşuları
  analytics/        SQL agregasyon · rapor dışa aktarma
  notification/     ETA hesabı · uygulama içi + e-posta kanalları
```

```
backend/src/main/resources/
  db/changelog/     Liquibase — db.changelog-master.yaml + sürümlü dosyalar
  application.yml   profiller: default, docker, test
```

### Python servisi modül düzeni

```
python/app/
  main.py              FastAPI uygulaması · açılışta graf yükleme
  config.py            ortam değişkenleri
  schemas/             pydantic istek/yanıt modelleri
  graph/
    model.py           Graph · Node · Edge
    loader.py          ikili graf dosyasını belleğe yükleme
  pathfinding/
    base.py            ortak arayüz · metrik toplama (süre, gezilen düğüm)
    dijkstra.py  astar.py  bfs.py  dfs.py
  vrp/
    model.py           Solution · Route · Stop
    objective.py       amaç fonksiyonu — maliyet hesabı
    feasibility.py     kapasite + maksimum süre kontrolleri
    construction/      savings.py · nearest_neighbor.py · naive.py
    localsearch/       two_opt.py · relocate.py · exchange.py · or_opt.py
    metaheuristic/     simulated_annealing.py
    exhaustive.py      DFS tam arama (n ≤ 10)
    routewise.py       aşamaları birleştiren orkestratör
  analytics/
    aggregate.py       pandas özetleri
    significance.py    scipy Wilcoxon / t-test
    figures.py         matplotlib figürleri
    export.py          Excel (openpyxl) · PDF
  api/
    optimize.py  compare.py  analytics.py  health.py
python/tests/
```

### Frontend klasör düzeni

```
frontend/src/
  main.tsx  App.tsx  router.tsx
  lib/          apiClient · authStore · queryClient
  types/        API tipleri — zod şemalarından türetilir
  components/
    ui/         düğme, form alanı, tablo, modal
    map/        MapLibre sarmalayıcıları · rota katmanı · durak işaretleyici
    charts/     Recharts sarmalayıcıları
    layout/     kabuk, yan menü, rol bazlı navigasyon
  features/
    auth/ shipments/ addresses/ verification/ planning/
    routes/ comparison/ reports/ courier/ recipient/
```

### Görevler
1. Gradle projesi + Spring Boot başlat, `RouteWiseApplication` ayağa kalksın
2. Liquibase master changelog + **tüm tabloların şeması** (15 tablo, sayfa 7–9'daki alanlar)
3. Seed verisi: depo (1), filo (sabit), test kullanıcıları (üç rol)
4. Python: FastAPI iskeleti + `/health` + graf yükleyici (gerçek graf dosyasını okusun)
5. Frontend: Vite + React + TS + yönlendirme + boş sayfalar
6. Üçünü de Docker'dan ayağa kaldır, birbirine erişimi doğrula

### Biten iş tanımı  ✅ TAMAMLANDI (12.10.2026)
- [x] `docker compose up` → backend `/actuator/health` ve python `/health` 200 dönüyor
- [x] Liquibase 15 tabloyu oluşturuyor (22 changeset), yeniden çalıştırmada tekrar uygulamıyor
- [x] Python servisi gerçek grafı yüklüyor: 9.797 düğüm, 24.181 yönlü kenar, 1.259,2 km
- [x] Frontend açılıyor, backend üzerinden Python servisine ulaşıyor (`/system/status`)
- [x] Python testleri koşuyor — 9 test
- [x] Bütünlük kuralları sınandı: 7 denemenin 7'si doğru davrandı

**Not:** Backend host portu 8090'dır (8080 değil). Bu makinede 8080–8086 aralığı
başka bir projeye ait; `infra/.env` içindeki `BACKEND_PORT` ile değiştirilebilir.

### Risk
- **Şema baştan yanlış kurulursa** sonraki her faz etkilenir. Sayfa 7–9'daki alan listelerine
  ve bütünlük kurallarına birebir uy; `ON DELETE` davranışlarını atlamayın.
- **Hibrit servis iletişimi** → backend'den python'a örnek bir çağrı bu fazda denenmeli,
  sonraki faza bırakılmamalı.

---

## Faz 1b — Arayüz Tasarımı (1,5 hafta)

### Amaç
Üç rolün tüm ekranları tasarlanmış; tıklanabilir prototip ve PDF wireframe hazır.

### Ekran envanteri

**Firma (kargo şirketi)**

| # | Ekran | İçerik |
|---|---|---|
| F1 | Giriş | e-posta + parola |
| F2 | Gösterge paneli | günün özeti: kargo sayısı, araç, tahmini maliyet, bekleyen doğrulama |
| F3 | Kargo listesi | filtre, durum rozetleri, yeni kargo düğmesi |
| F4 | Yeni kargo | alıcı seçimi, ağırlık, öncelik — sipariş no otomatik |
| F5 | **Adres doğrulama kuyruğu** | bekleyen adresler, harita önizleme, onayla/reddet |
| F6 | Rota planlama | harita + araç bazlı rotalar + optimize et + ilerleme göstergesi |
| F7 | Rota detayı | durak sırası, ETA'lar, yük, süre, maliyet kırılımı |
| F8 | Algoritma karşılaştırma | koşu başlat, Seviye 1 ve Seviye 2 tabloları |
| F9 | Tasarruf raporları | grafikler, dönem seçimi, Excel/PDF indirme |

**Kurye**

| # | Ekran | İçerik |
|---|---|---|
| K1 | Giriş | — |
| K2 | Bugünkü rotam | sıralı durak listesi, ETA, toplam km/süre |
| K3 | Durak detayı | adres, alıcı, paket, "teslim edildi" / "ulaşılamadı" |
| K4 | Harita görünümü | rota çizimi, sıradaki durak vurgulu |
| K5 | Rota değişti bildirimi | neyin değiştiği açıkça — eski durak kaldırıldı/eklendi |

**Alıcı**

| # | Ekran | İçerik |
|---|---|---|
| A1 | Giriş | — |
| A2 | Kargolarım | durum, tahmini teslim saati |
| A3 | Kargo detayı | ETA, "başka gün gelsin" düğmesi |
| A4 | Adresim | haritadan konum düzenleme + doğrulama durumu rozeti |
| A5 | Bildirimler | ETA, erteleme, doğrulama sonucu |

### Görevler
1. Ekran envanterini dondur (yukarıdaki 19 ekran)
2. Her ekran için **durum varyantları**: boş, yükleniyor, hata, dolu
3. Kritik akışları çiz: kargo oluşturma → doğrulama → planlama → teslim; adres değiştirme → yeniden rotalama
4. Tıklanabilir HTML prototip üret (gerçekçi veriyle, ekranlar arası geçiş çalışır)
5. Prototipten PDF wireframe türet

### Biten iş tanımı
- [ ] 19 ekranın tamamı prototipte var ve birbirine bağlı
- [ ] Üç rolün ana akışı baştan sona tıklanabiliyor
- [ ] Her ekranın boş/hata durumu tasarlanmış
- [ ] PDF wireframe `docs/` altında
- [ ] **Prototip gözden geçirildi ve eksik gereksinim kalmadığı onaylandı**

### Risk
- **Ekran tasarımı gereksinim açığı ortaya çıkarır** — bu iyi bir şeydir, ama mimariye
  geri dönüp `architecture.md` ve veri modelini güncellemeyi gerektirebilir. Bunun için
  zaman payı bırakıldı.

---

## Faz 1c — API Sözleşmesi (0,5 hafta)

### Amaç
Frontend ve backend'in anlaştığı, yazılı ve değişmez bir arayüz.

### Görevler
1. Ekranlardan **gereken** endpoint'leri türet (fazlasını yazma)
2. Her endpoint için: yol, metot, yetki rolü, istek gövdesi, yanıt gövdesi, hata kodları
3. Ortak hata biçimi: `ApiError { code, message, fieldErrors[] }`
4. Sayfalama, sıralama, filtreleme kuralları
5. OpenAPI/Swagger üretimini aç
6. Frontend tiplerini zod şemalarıyla eşle

### Biten iş tanımı  ✅ TAMAMLANDI (15.10.2026)
- [x] Her ekranın ihtiyacı bir uçla karşılanıyor, karşılıksız uç yok —
      izlenebilirlik tablosu: [api-contract.md](api-contract.md) §7
- [x] Yetki matrisi her uca uygulanmış (§6); "kendi" kısıtı 404 ile döner, 403 ile değil
- [x] Swagger arayüzü açılıyor — `http://localhost:8090/swagger-ui.html`
- [x] Ortak hata biçimi uygulandı: `ApiError` + `GlobalExceptionHandler` + 14 kodluk katalog
- [x] Postman collection üretildi — 44 istek, 8 klasör, değişkenler gömülü,
      environment dosyası yok (`node docs/api/build-postman.mjs`)
- [x] Taban yol `/api/v1` koda uygulandı (`WebMvcConfig`); actuator ve Swagger kökte kaldı

**Sözleşme ile kod örtüşmesi:** `/api/v1/system/status` 200, eski `/system/status` 404.
Uçların gövdeleri Faz 2'den itibaren yazılır; bu faz sözleşmeyi ve altyapıyı teslim eder.

---

## Faz 2 — Katman 1: Kargo Listesi, Adres Doğrulama, Tek Araç (2,5 hafta)

### Amaç
Kargo ekleyip çıkarabiliyoruz; adres haritadan seçiliyor, beş katmanlı kontrolden geçiyor,
firma doğruluyor; tek araç için rota hesaplanıp haritada çiziliyor.

### Görevler

**Backend**
1. ✅ Auth + JWT + üç rol — giriş, yenileme (dönüşümlü), çıkış (gerçekten geçersizler), /auth/me
2. ✅ Kargo CRUD + sipariş numarası üretimi (`BND-YYYY-NNNNN`)
3. ✅ Adres kaydı + **beş katmanlı doğrulama**:
   - geofence (`ST_Contains` Bandırma poligonu)
   - yola snap (`ways_vertices_pgr`'da en yakın düğüm + eşik kontrolü)
   - ters geocoding ile adres metni onayı
   - aykırı değer uyarısı
   - firma onay/ret akışı + durum makinesi
4. ✅ `DistanceMatrixProvider` + kendi Dijkstra'mız + `distance_matrix_cache`
5. ✅ Adres günlük değişiklik sayacı gün dönümünde sıfırlanıyor — `change_count_date` (030);
   gün Europe/Istanbul'a göre döner (`routewise.time-zone`)
6. ✅ Depo düğümü: ilk kullanımda adreslerle **aynı** snap sorgusuyla grafa bağlanıyor
7. ✅ `GET /recipients?q=`, `GET /recipients/{id}/addresses` — `usableForRouting` sunucuda
8. ✅ `OptimizerClient` + `POST /plans`, `POST /plans/{id}/optimize` (tek araç),
   `GET /plans/{id}/runs`, `GET /runs/{id}` (GeoJSON geometri), `GET /vehicles`;
   duraklar koşu anındaki graf düğümüyle saklanıyor (031)
8a. ✅ `/me/addresses` GET/POST/PUT öne alındı — adres oluşturan başka uç yoktu
8b. ✅ Testcontainers altyapısı (projenin kendi PostGIS+pgRouting imajı) — ilk repository testleri

**Python**
9. ✅ Graf yükleyici + `dijkstra.py` — **pgRouting ile doğrulandı, 125 çiftte tam mutabakat**
10. ✅ Basit TSP: tek araç, nearest neighbor + naive baseline (aynı durak kümesinde)
11. ✅ `POST /internal/optimize` ilk sürüm — olurluluk testleri algoritmadan önce yazıldı
12. ✅ Matris üretimi: `single_source` ile N kez tek-kaynak koşusu (Python üretir, backend cache'ler)
13. ✅ `POST /internal/route-geometry` — haritada gerçek yolu çizmek için bacak koordinatları

**Frontend**
14. ✅ Temel: yönlendirme, TanStack Query, auth store + paylaşılan token yenileme, MapLibre
    (harita ekranları tembel yüklenir; ana paket ~470 KB)
15. ✅ F1, F3, F4, F5 + A4 (alıcı haritadan konum seçer, kaydetmeden doğrulanır)
16. ✅ F6 (Faz 2 sürümü): plan aç, optimize et, naive'e göre mesafe tasarrufu, dışlanan
    kargolar sebebiyle, rota gerçek yol geometrisiyle haritada
17. ✅ Testler: Python 102 · backend 114 (Testcontainers dahil) · frontend 22 (vitest + MSW)

**Faz 2 DoD durumu (30.10.2026, gerçek yığında uçtan uca):**
Bandırma dışı nokta reddediliyor ✅ · doğrulanmamış/taşınmış adresli kargo rotaya
girmiyor ✅ · adres taşınınca DOGRULAMA_BEKLIYOR ✅ · Dijkstra = pgr_dijkstra ✅ ·
matris cache ikinci koşuda isabet ✅ · 9–10 duraklı liste haritada sıralı rota ✅ ·
**açık:** snap eşiğinin 20–30 gerçek Bandırma adresiyle elle sınanması

### Biten iş tanımı
- [ ] Haritadan Bandırma dışına tıklanınca **reddediliyor**
- [ ] Yoldan uzağa tıklanınca uyarı veriyor, snap mesafesi kaydediliyor
- [ ] Doğrulanmamış adresli kargo rotaya **girmiyor**
- [ ] Alıcı adresi değiştirince durum otomatik `DOĞRULAMA_BEKLİYOR`'a düşüyor
- [ ] Kendi Dijkstra'mız `pgr_dijkstra` ile **aynı mesafeyi** veriyor (test)
- [ ] Matris cache çalışıyor: aynı durak kümesi ikinci kez hesaplanmıyor
- [ ] 10 teslimatlık bir liste haritada sıralı rota olarak çiziliyor

### Risk
- **Snap eşiği yanlış ayarlanırsa** geçerli adresler reddedilir veya geçersizler kabul edilir.
  Bandırma'dan gerçek 20–30 adresle elle sına.
- **Dijkstra doğrulaması tutmazsa** graf yüklemesinde veya ağırlık alanında hata vardır —
  sonraki her şey buna dayandığı için burada durulmalı.

---

## Faz 3 — Katman 2: CVRP (3 hafta) ⚠ en riskli faz

### Amaç
Çok araç, kapasite sert kısıtı, tam maliyet modeli, RouteWise algoritması çalışıyor.

### Görevler
1. ✅ `vrp/model.py` — Solution, Route, Stop, Vehicle (yakıt, sabit gider), CostParams
2. ✅ `objective.py` — yakıt + sürücü + araç sabit = **TL maliyet**; + öncelik cezası = **amaç**.
   Ceza tanımı: `katsayı × Σ öncelik × varış_saati` (zaman penceresi olmadığı için "ihlal" yok)
3. ✅ `feasibility.py` — kapasite, sürülebilirlik, kapsama, filo; tek kontrol noktası `RouteSet.delta`
4. ✅ `construction/savings.py` — Clarke-Wright; asimetrik matris + farklı kapasiteli filo
5. ✅ `localsearch/` — 2-opt, relocate, exchange, Or-opt (round-robin ilk iyileştirme)
6. ✅ `metaheuristic/simulated_annealing.py` — tohumlanmış, iterasyon bütçeli, sıcaklık veriden
7. ✅ `routewise.py` — savings → LS → SA → LS; sıcak başlangıç yalnızca etkilenen rotalarda
8. ✅ `construction/naive.py` — ilk sığan araç, geliş sırası; her koşuda aynı kümede
9. ✅ Backend: plan gününün maliyet parametreleri + tüm aktif filo + tohum + sıcak başlangıç
   (`warmStartFromRunId`) → `/internal/optimize`; koşu ve rota bazında `total_cost_try`,
   `total_fuel_l`, `random_seed` kalıcılaştırma; TL bazlı `savingsPct` (032)
10. ✅ Frontend: F6 araç başına renkli rota + "mevcut rotadan devam et", F7 rota maliyet kırılımı
11. ✅ Granüler komşuluk — relocate/exchange/Or-opt yalnızca en yakın K=12 durağın yanına
    dener. Gerçek grafta 3'er örnek: n=100 10,8–15,7 sn → 3,4–4,5 sn (~3,4×), amaçta ortalama
    +%0,35 (en kötü +%1,36, bir örnekte −%0,76). K=8 ek hız getirmedi, K=20 yavaş ve daha iyi değil.
12. ✅ Sevk: `POST /plans/{id}/dispatch` — uygulanan koşunun rotalarına müsait kuryeler id sırasıyla
    atanır, kargolar PLANLANDI, plan kilitlenir; kurye yetmezse ya da koşudan sonra kargo
    değiştiyse açık 409. `GET /routes/{id}` (F7)
13. ✅ Hata: koşuda geçmiş kargoyu silmek `route_stops` yabancı anahtarına çarpıp 500 veriyordu →
    409 `SHIPMENT_IN_RUN_HISTORY` (koşular ölçüm verisidir, değiştirilmez)

**Python DoD durumu (31.10.2026):** olurluluk özellik testleri (her operatörün her adayı,
kontrol bozulunca 55 test düşüyor) ✅ · aynı tohum aynı rota ✅ · sıcak başlangıç önceki
ardışık çiftlerin ≥%80'ini koruyor ✅ · naive her koşuda ✅ · n=7 iki araçta tam aramaya
sapma ≤%2 ✅ · gerçek grafta ceza=0 iken RouteWise ≤ SAVINGS ≤ NN ≤ NAIVE ✅

**Karar (30.10.2026) — öncelik cezası 25 → 2,5 TL/(öncelik·saat)** (032): 25 ile ceza TL
maliyetin 2–3 katına çıkıyor, RouteWise öncelikli kargoyu erkene çekmek için ek araç açıyordu.

**Düzeltme (30.10.2026):** Python'un otomatik camelCase alias'ı `fuelLPer100Km` üretiyor,
backend `fuelLPer100km` gönderiyordu; alan sessizce atıldı ve **yakıt maliyeti hiç hesaba
girmedi**. Alias açıkça verildi ve istek modelleri artık bilinmeyen alanı 422 ile reddediyor.
Yukarıdaki 25'e karşı ölçüm yakıtsızdı; karar düzeltilmiş modelle yeniden ölçüldü.

Gerçek grafta, seed filo (3 araç), ceza 2,5, yakıt dahil — TL maliyet (öncelik cezası hariç):

| n | NAIVE | GREEDY_NN | SAVINGS | ROUTEWISE | RouteWise süre |
|---|---|---|---|---|---|
| 20 | 1.345 | 1.041 | 1.248 | **1.015** | 0,3 sn |
| 50 | 2.572 | 1.634 | 1.500 | **1.495** | 1,2 sn |
| 100 | 4.811 | **2.478** | 2.658 | 2.561 | 15,7 sn |

n=100'de RouteWise parada NN'in 82 TL gerisinde ama gecikme cezası 147'ye karşı 549:
ceza büyük planlarda hâlâ tercihleri yönlendiriyor. Tezde "para–öncelik dengesi" olarak
raporlanmalı ya da katsayı yeniden ayarlanmalı. (Süre granüler komşulukla düşürüldü — madde 11.)

### Biten iş tanımı
- [ ] **Hiçbir yerel arama hamlesi kapasite ihlali üretmiyor** (olurluluk testleri yeşil)
- [ ] Aynı tohumla aynı girdi **aynı rotayı** veriyor (determinizm testi)
- [ ] Sıcak başlangıç: tek paket eklenince rota baştan aşağı değişmiyor
- [ ] Naive baseline her koşuda hesaplanıp kaydediliyor
- [ ] Küçük örnekte (n ≤ 8) sonuç, tam aramayla bulunan optimumun makul yakınında
- [ ] Maliyet kırılımı ekranda TL olarak görünüyor

### Risk
- **Bu projenin en riskli fazıdır.** Algoritma çalışmazsa proje çalışmaz.
- Olurluluk testleri **önce** yazılmalı; sonra algoritma. Aksi hâlde sessizce geçersiz
  rota üreten bir sistemle aylarca çalışılır.
- SA parametreleri (başlangıç sıcaklığı, soğuma oranı, iterasyon) ayar gerektirir —
  bunun için zaman payı ayrıldı.

---

## Faz 4 — Katman 3: Süre Kısıtı, ETA, Bildirim, Roller (2 hafta)

### Amaç
Gün sınırı sert kısıt olarak devrede; alıcı ETA bildirimi alıyor; üç rol tam çalışıyor.

### Görevler
1. ✅ Maksimum rota süresi katı kısıt: `max_duration_s` (depo `max_route_duration_min`), tek kontrol
   noktası `RouteSet.delta`; testler önce yazıldı (süre kontrolü yokken 24 test düşüyordu)
2. ✅ Süre hesabı: yol süresi (graf, `maxspeed`) + teslim süreleri + depoya dönüş
3. ✅ ETA: Python durak başına `arrivalS`, backend `plan günü + depo açılışı (İstanbul) + arrivalS`
   → `route_stops.eta`; F6'da "Varış" sütunu
   - Tek başına güne sığmayan durak hata değil: `EXCEEDS_WORKDAY` ile dışarıda kalır
   - **Bulgu:** sıkı günde naive (geliş sırası) zikzak çizdirip kurulamıyor; her koşuda
     hesaplandığı için asıl optimizasyonu düşürüyordu. Artık baseline kurulamazsa optimize
     çözüm yine döner, `baseline: null` + `baselineError` raporlanır
4. ✅ Bildirim altyapısı: uygulama içi bildirim işle aynı transaction'da (iş geri alınırsa bildirim de
   yok); e-posta commit sonrası `MailPort` üzerinden. **E-posta şimdilik yalnızca loglanıyor** —
   `spring-boot-starter-mail` + yerel SMTP (ör. Mailpit) kararı bekliyor. EMAIL satırı yalnızca
   gerçekten gönderilince yazılır. Tetikleyiciler: sevkte ETA, adres onay/ret (sebebiyle)
5. ✅ Rol bazlı yetkilendirme tüm endpoint'lerde — `AccessMatrixTest` her ucu her role karşı gerçek
   filtre zinciri ve gerçek JWT ile dener (yetkisiz 403, oturumsuz 401, yetkili geçer); uygulamaya
   matrise yazılmamış bir uç eklenirse test düşer. Durak kapatma kuralı filtreye de eklendi
   (yalnızca controller'dayken gövde doğrulaması önce çalışıp firmaya 400 dönüyordu)
6. ✅ Kurye ekranları K1–K4 + `/courier/**`: günün sevk edilmiş rotası (yoksa 404 → "bugün rotan yok"),
   durak detayı (alıcı adı, telefon, ETA), teslim / ulaşılamadı (sebep zorunlu, 033).
   İlk kapanan durak rotayı YOLDA'ya, açık kargoları YOLDA'ya (adres kilidi) alır; son durak
   rotayı, son rota planı tamamlar; ulaşılamayan kargo havuza döner. Başkasının durağı 404,
   firma okur ama kapatamaz (403), çift dokunuş 409 `STOP_ALREADY_CLOSED`
7. ✅ Alıcı ekranları A1–A5: kargolarım (tek cümlelik beklenti: tahmini saat / teslim / ulaşılamadı),
   kargo detayı (kurye notu), bildirimler (okunmamış sayısı menüde, metin payload'dan ekranda kurulur).
   Başkasının kargosu 404. Erteleme (`/me/shipments/{id}/postpone`) Faz 5'te

### Biten iş tanımı
- [ ] Gün sınırını aşan çözüm **üretilmiyor**
- [ ] ETA'lar makul (elle birkaç durak için doğrula)
- [ ] Alıcı e-posta ve uygulama içi bildirim alıyor
- [x] **Rol sızıntısı testleri yeşil** — alıcı başkasının teslimatını göremiyor (`OwnershipLeakTest`:
  kargo, adres, bildirim; 404 ve gövdede iz yok)
- [x] Kurye yalnızca kendi rotasını görüyor (başkasının durağı 404, `courierId` kuryede yok sayılır)

---

## Faz 5 — Katman 4: Dinamik Yeniden Rotalama (2 hafta)

### Amaç
Dağıtım sırasında erteleme ve adres değişikliği rotayı canlı olarak güncelliyor.

### Görevler
1. ✅ "Başka gün gelsin" → `ERTELENDİ` + kümeden çıkarma + yeniden optimizasyon —
   `POST /me/shipments/{id}/postpone` (yarın … +14 gün). Erteleme önce ve tek başına commit
   edilir: optimizer kapalıysa bile geçerlidir, durak kuryenin listesinden düşer, yalnızca kalan
   ETA'lar eski kalır. Durak satırı silinmez, `ERTELENDI` olur (034); sevkten sonra başka güne
   ertelenen kargo taslak koşudan sevk edilemez
2. ✅ Dağıtım sırasında adres değişikliği → doğrulamaya düşme → onay sonrası yeniden rotalama —
   kurye çıkmadan (kargo `PLANLANDI`) alıcı taşıyabilir; durak eski düğümde bekler. Firma onaylarsa
   durak yeni düğüme taşınır ve rota yeniden hesaplanır (aynı düğüme düşerse hesaplanmaz);
   reddederse durak çıkar, kargo adres düzelene kadar havuza döner. Adres modülü rotaları bilmez:
   `AddressService.Verified/Rejected` olayı, `AddressRerouteHandler` dinler. Sevk de artık koşudan
   sonra taşınan ya da onayını kaybeden adresli kargoyu reddediyor
3. ✅ **Son tamamlanan duraktan** yeniden optimizasyon (canlı GPS yok) — yalnızca o kuryenin
   rotası (kargo fiziksel olarak o araçta). Python'a `startNodeId`: problem matrisinde 0 indeksinin
   satırı kuryenin konumundan, sütunu depodan okunur, algoritmaların hiçbiri değişmedi. Kalan süre
   = mesai sonu − max(şimdi, planlı çıkış); mevcut sıra hem naive baseline hem sıcak başlangıç,
   tohum rota kimliği. Her yeniden rotalama `reroute_events`'e yazılır (eski sıra maliyeti / yeni
   maliyet — Faz 6 analizi için). Oku → hesapla (transaction dışında) → rota kilidi altında yaz;
   arada açık duraklar değiştiyse sonuç atılır, yeniden hesaplanır
4. Beş güvenlik önlemi:
   - ✅ olurluluk kırılırsa otomatik erteleme — birlikte sığmıyorlarsa önceliği en düşük (eşitse
     en sonda) olan çıkar; tek başına sığmayan da çıkar; ertesi güne, alıcıya sebebiyle bildirim
   - ✅ `YOLDA` durumunda adres kilidi — alıcının kendi adres ekranında eksikti (yalnızca firmanın
     kargo düzenlemesinde vardı); artık yoldaki kargonun adresi taşınamaz, 409 `ADDRESS_LOCKED`
   - ✅ maliyet artışı eşiği → erteleme önerisi — onaylanan adres değişikliğiyle taşınan durak için
     kalan tur bir kez de o durak olmadan hesaplanır; fark kalan turun %15'ini aşarsa
     (`routewise.reroute.suggest-postpone-above`) firmaya F5'te öneri düşer (036). Firma "Yarına
     ertele" der (alıcıya bildirilir, kuryede `FIRMA_ERTELEDI`) ya da "Bugün kalsın". Karar insanda;
     sistem kendiliğinden ertelemez. Ölçülemezse yeniden rotalama yine geçerli, yalnızca öneri yok
   - ✅ günlük değişiklik limiti — rota başına günde 10 yeniden sıralama
     (`routewise.reroute.max-per-route-per-day`); sonrasında çıkan durak çıkar ama kalan sıra
     değişmez, kuryeye `orderKept` ile bildirilir. Alıcının günlük adres değiştirme hakkı (2) ayrıca var
   - ✅ kuryeye açık bildirim (`ROTA_DEGISTI`: kalan durak, otomatik ertelenenler); ETA'sı 15 dk'dan
     fazla kayan alıcıya yeni saat bildirimi
5. ✅ Debounce (~500 ms) + asenkron koşu — `RerouteScheduler`: istek commit sonrası kuyruğa girer,
   aynı rotaya pencere içinde gelen istekler tek koşuda birleşir (son tetikleyici kaydedilir); hata
   loglanır, değişikliğin kendisi kayıtlı kalır. Testlerde pencere 0 (eşzamanlı, test transaction'ı
   commit edilmediği için). İlerleme göstergesi: `RerouteScheduler.isBusy` — değişiklik commit
   edildiği andan koşu bitene kadar rota "hesaplanıyor"; kurye ekranı bu sürede 2 sn'de bir, sonra
   30 sn'de bir sorar ve hesaplama bitince rotayı tazeler
6. ✅ Ekranlar — alıcı A3'te "Başka gün gelsin" (yarın … +14 gün, yoldaysa "kurye bugün uğramaz"
   uyarısı); kurye K2'de "Rota değişti" bandı ve K5 `/kurye/degisiklikler`: çıkan durak sebebiyle,
   adresi değişen durak, numarası değişen durak eski ve yeni yeriyle. Her değişiklik
   `reroute_events.changes`'e yazılır (035); pencerede birleşen tüm değişiklikler listelenir, bir
   çıkış yalnızca bir kez gösterilir. Limit dolduğunda da kayıt düşer (`order_kept`), limite sayılmaz

### Biten iş tanımı
- [x] Erteleme sonrası rota yeniden hesaplanıyor, teslim edilenler sabit kalıyor (`RerouteFlowTest`)
- [x] Adres değişikliği gün sınırını aşırırsa teslimat **otomatik erteleniyor** — onaydan sonraki
  yeniden rotalama aynı yoldan geçer (sığmayan ertesi güne)
- [x] `YOLDA` teslimatın adresi değiştirilemiyor
- [x] Günlük değişiklik limiti devrede
- [x] Kurye ekranında eski durak kalkıyor, bildirim düşüyor — durak "Ertelendi · rotadan çıktı" olarak
  kalır, haritadan ve ilerlemeden düşer; "Rota değişti" bandı ve K5 ekranı ne değiştiğini söyler
- [x] Arka arkaya 10 değişiklikte 10 değil 1 optimizasyon koşuyor (`RerouteSchedulerTest`)

---

## Faz 6 — Katman 5: Karşılaştırma ve Analiz (2,5 hafta)

### Amaç
Projenin en görünür çıktısı: algoritma karşılaştırması, tasarruf analizi, raporlar.

### Görevler

**Seviye 1 — yol bulma**
1. ✅ `astar.py`, `bfs.py`, `dfs.py` implementasyonları — A* sezgiseli kuş uçuşu (süre ağırlığında
   / graftaki en yüksek hız, ×0,99 güvenlik payı); DFS açık yığınla (özyineleme sınırı yok)
2. ✅ Ortak metrik toplama: yol uzunluğu ve süresi (aynı kenarlar üzerinden), gezilen düğüm, kenar
   sayısı, hesaplama süresi (N koşunun medyanı), Dijkstra'dan sapma % — `pathfinding/compare.py`
3. ✅ `path_comparison_runs` kayıtları — karşılaştırma başına `comparisons` satırı (girdi + tam sonuç,
   F8 yeniden çizebilsin), algoritma başına metrik satırı (037)
4. ✅ `POST /compare/path` — uçlar haritadan nokta (yola bağlanır) ya da düğüm kimliği;
   `GET /comparisons/{id}`

   Gerçek Bandırma grafında 30 rastgele çift (mesafe ağırlığı, seed 42):

   | Algoritma | Sapma medyan | Sapma en kötü | Gezilen düğüm medyan | Süre medyan |
   |---|---|---|---|---|
   | Dijkstra | %0 | %0 | 6.276 | 19,1 ms |
   | A\* | %0 (30/30 aynı yol) | %0 | 1.395 (Dijkstra'nın %30,5'i) | 8,0 ms |
   | BFS | %8,9 | %70,6 | 5.558 | 7,6 ms |
   | DFS | %890,6 | %6.502,6 | 4.752 | 8,3 ms |

**Seviye 2 — tur sırası**
5. ✅ `nearest_neighbor.py` (Faz 2'den), `exhaustive.py` (n ≤ 10) — tek araç, dal-sınır DFS: önek
   maliyeti + kalan durakların en az cezası alt sınırdır, en iyi sezgisel başlangıç üst sınırı.
   Kanıtlı optimum; 7 duraklı rastgele örneklerde kaba kuvvetle (tüm permütasyonlar) aynı sonuç
6. ✅ `POST /plans/{id}/compare/tour` — tek araç, planın doğrulanmış kargoları ya da `shipmentIds` alt
   kümesi; NAIVE, GREEDY_NN, SAVINGS, ROUTEWISE + n ≤ 10 ise DFS_EXHAUSTIVE aynı matris, maliyet ve
   çalışma günüyle. Plan değişmez. Sonuç `comparisons`'a, algoritma başına satır
   `tour_comparison_runs`'a (038) — anlamlılık testinin girdisi. Çok araçlı kesin arama kapsam dışı:
   10 durakta bile atama × sıra on milyonlarca dal
7. ✅ Her algoritma N kez, süre medyanı raporlanır; ROUTEWISE her tekrarda başka tohumla, amacı medyan
   olan koşu raporlanır, en iyi/en kötü amaç da verilir

   Gerçek grafta 10 rastgele senaryo (Bandırma merkezi, 10 durak, 8 saatlik gün, 3 tekrar):

   | Algoritma | Optimumdan sapma medyan | En kötü | Optimumu bulduğu |
   |---|---|---|---|
   | NAIVE | %26,0 | %42,2 | 0/10 |
   | GREEDY_NN | %2,6 | %8,9 | 0/10 |
   | SAVINGS | %1,0 | %3,4 | 1/10 |
   | ROUTEWISE | %0,0 | %0,0 | 10/10 |

   DFS_EXHAUSTIVE: medyan 1,4 sn, en kötü 2,8 sn; 3,6 milyon sıradan medyan 689 bin arama düğümü açılır.
   Bu bir ön bakıştır; iddia 30+ senaryo ve anlamlılık testiyle (Analiz) kurulacak

**Analiz**
8. `aggregate.py` — haftalık/aylık/yıllık pandas resample
9. ✅ `significance.py` — Wilcoxon işaretli sıralar (ana test, zsplit, tek yönlü H1: RouteWise daha ucuz)
   + eşleştirilmiş t-testi (destek). Senaryo üretici `analysis/scenarios.py`: tohumdan, gerçek
   Bandırma grafında depo çevresinde (varsayılan 3 km) ulaşılabilir düğümler; yük/servis/öncelik de
   tohumdan. `POST /compare/experiments` N senaryoyu arka planda tek tek koşar (her biri bir
   `comparisons` satırı, 039), ilerleme izlenir, sonunda özet ve iddia cümlesi yazılır. Aynı anda tek
   deney; sunucu yeniden başlarsa yarım deney FAILED olur (aynı tohumla yeniden başlatılır)

   Gerçek ortamda deney (30 senaryo, 10 durak, 3 km, 8 saatlik gün, 3 tekrar, tohum 2026; 271 sn):

   | Algoritma | Optimumdan sapma medyan / ort. / en kötü | Optimumu bulduğu | Süre medyan |
   |---|---|---|---|
   | NAIVE | %17,3 / %17,2 / %33,2 | 0/30 | 0,01 ms |
   | GREEDY_NN | %3,9 / %4,4 / %13,6 | 0/30 | 0,05 ms |
   | SAVINGS | %0,7 / %0,9 / %3,0 | 4/30 | 0,11 ms |
   | ROUTEWISE | %0 / %0 / %0 | 30/30 | 239 ms |
   | DFS_EXHAUSTIVE | — (referans) | 30/30 | 2,5 sn |

   RouteWise her karşılaştırmada anlamlı ölçüde daha ucuz (tek yönlü, n = 30): NAIVE'e göre
   Wilcoxon p = 9,3·10⁻¹⁰ (ort. %14,5 ucuz), GREEDY_NN'e göre p = 9,3·10⁻¹⁰ (%4,2), SAVINGS'e göre
   p = 1,4·10⁻⁶ (%0,9; 4 senaryoda eşit)
10. `figures.py` — matplotlib figürleri (prototip: `docs/diagrams/cost_comparison.py`)
11. `export.py` — Excel (5 sayfa) + PDF rapor
12. Frontend: F8, F9 — karşılaştırma tabloları, Recharts grafikleri, indirme
    - ✅ F8 `/firma/karsilastirma` — Seviye 1: haritada A ve B seç, ağırlık ve tekrar; dört algoritmanın
      tablosu, seçilenlerin yolu haritada, gezilen düğüm ve sapma grafikleri. Seviye 2: plan günü, araç,
      kargo alt kümesi, tekrar; referansın kanıtlı optimum mu en iyi bulunan mı olduğu açıkça yazar,
      sapma ve hesap süresi grafikleri. Recharts 3.10.1 (sabit sürüm), yalnızca bu ekranın tembel
      parçasında (~114 kB gzip)
    - F9 tasarruf raporları ve indirme (Analiz adımlarıyla)

### Biten iş tanımı
- [x] BFS'in ağırlıkları yok saydığı **sayısal olarak gösteriliyor** (Dijkstra'dan sapma %) — medyan %8,9, en kötü %70,6
- [x] A\*, Dijkstra ile aynı yolu daha az düğüm gezerek buluyor — 30/30 aynı yol, gezilen düğüm %30,5
- [x] DFS-exhaustive küçük örnekte gerçek optimumu veriyor — kaba kuvvetle karşılaştırma testi, çalışma günü kısıtı dahil
- [x] "RouteWise optimumun %X içinde" cümlesi veriyle destekleniyor — 30/30 senaryoda optimumda (%0); deney özetinin `claim` alanı
- [x] En az **30 senaryoda** anlamlılık testi koşuldu, p değeri raporlanıyor — Wilcoxon + eşleştirilmiş t, üç karşılaştırmada p < 0,0001
- [ ] Excel ve PDF raporları indirilebiliyor
- [ ] Haftalık/aylık/yıllık tasarruf grafikleri çalışıyor

### Risk
- **Yeterli senaryo üretilmezse** anlamlılık testi anlamsız kalır. Senaryo üretici
  (rastgele teslimat kümeleri) bu fazda yazılmalı.

---

## Faz 7 — Kapanış (2 hafta)

### Görevler
1. Test boşluklarını kapat — hedef: satır ≥ %80, dal ≥ %70
2. Linter/formatter temiz: spotless · ruff + mypy · eslint + prettier
3. `README.md` — sıfırdan kurulum, ekran görüntüleri
4. `architecture.md`'yi son hâle güncelle (veri modeli bölümü dahil)
5. Tez raporu: matplotlib figürleri, tablolar, sonuç bölümü
6. Sunum hazırlığı + demo senaryosu provası
7. Bilinen eksikler ve gelecek çalışma listesi

### Biten iş tanımı
- [ ] Temiz derleme, uyarısız
- [ ] Tüm testler yeşil
- [ ] Coverage hedefleri tutuyor
- [ ] `docker compose up` ile sıfırdan kurulum **başka bir makinede** denendi
- [ ] Demo senaryosu baştan sona provalandı
- [ ] Tez raporu teslim edilebilir durumda

---

## Genel riskler ve önlemler

| Risk | Erken uyarı | Önlem |
|---|---|---|
| Algoritma beklenen kaliteyi vermiyor | Faz 3 sonunda küçük örnekte optimuma uzak | SA parametre ayarı için zaman payı; gerekirse komşuluk yapısını genişlet |
| Kapsam genişlemesi (zaman penceresi, çok depo) | "Şunu da ekleyelim" konuşmaları | Katmanlı plana sadık kal; yeni istek bir sonraki katmana yazılır |
| Yol grafı sorunları | Faz 0'da `ways` satır sayısı tuhaf | Poligonu ve import loglarını kontrol et, Faz 0'dan çıkma |
| Takvim sıkışması | Faz 4 sonunda Faz 5'e vakit yok | Son çalışan katmanı sun — her faz demo edilebilir olduğu için bu güvenli |
| Sessiz geçersiz rotalar | Olurluluk testi yoksa hiç fark edilmez | Testleri algoritmadan **önce** yaz |
| Docker motoru | `docker info` hata veriyor | Faz 0'ın ilk maddesi |

## Değişmeyen kurallar

1. **Her katman çalışır kalır.** Yarım bırakılmış katman üstüne yeni katman başlamaz.
2. **Olurluluk testleri algoritmadan önce yazılır.** Sert kısıt ihlali sessizce geçerse
   sistem aylarca yanlış çalışır ve kimse fark etmez.
3. **Ölçüm altyapısı Faz 3'ten itibaren hazırdır.** Faz 6, veri üretmeyi beklemez.
4. **Python servisi veritabanına erişmez.** Bu kural hibrit mimarinin maliyetini düşüren
   tek şeydir.
5. **Kapsam genişletme talepleri bir sonraki katmana yazılır**, o anki katmanı bozmaz.
