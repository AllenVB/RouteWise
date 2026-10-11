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
   - `db`: `pgrouting/pgrouting:17-3.6-3.8`
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
- [ ] `docker compose up` ile dört servis ayağa kalkıyor, healthcheck'ler yeşil
- [ ] `ways` tablosunda Bandırma yol ağı var, satır sayısı makul (binler mertebesi)
- [ ] `pgr_dijkstra` örnek sorgusu mesafe döndürüyor
- [ ] `graphdata` volume'ünde graf dosyası var
- [ ] Kurulum adımları `README.md`'de belgelendi — **başka biri sıfırdan kurabilmeli**

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
service / controller / dto` katmanlarını içinde taşır. Bu, 13 tablolu bir projede
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
2. Liquibase master changelog + **tüm tabloların şeması** (13 tablo, sayfa 7–9'daki alanlar)
3. Seed verisi: depo (1), filo (sabit), test kullanıcıları (üç rol)
4. Python: FastAPI iskeleti + `/health` + graf yükleyici (gerçek graf dosyasını okusun)
5. Frontend: Vite + React + TS + yönlendirme + boş sayfalar
6. Üçünü de Docker'dan ayağa kaldır, birbirine erişimi doğrula

### Biten iş tanımı
- [ ] `docker compose up` → backend `/actuator/health` ve python `/health` 200 dönüyor
- [ ] Liquibase tüm tabloları oluşturuyor, `docker compose down && up` tekrarlanabilir
- [ ] Python servisi gerçek grafı yükleyip düğüm sayısını logluyor
- [ ] Frontend açılıyor, backend'e örnek bir istek atıp yanıt alıyor
- [ ] Testler boş da olsa koşuyor (`gradle test`, `pytest`, `vitest`)

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

### Biten iş tanımı
- [ ] Her ekranın ihtiyacı bir endpoint'le karşılanıyor, karşılıksız endpoint yok
- [ ] Yetki matrisi (sayfa 10) her endpoint'e uygulanmış
- [ ] Swagger arayüzü açılıyor
- [ ] Postman collection `~/Docs/2. Postman Collections/` altına çıkarıldı

---

## Faz 2 — Katman 1: Kargo Listesi, Adres Doğrulama, Tek Araç (2,5 hafta)

### Amaç
Kargo ekleyip çıkarabiliyoruz; adres haritadan seçiliyor, beş katmanlı kontrolden geçiyor,
firma doğruluyor; tek araç için rota hesaplanıp haritada çiziliyor.

### Görevler

**Backend**
1. Auth + JWT + üç rol
2. Kargo CRUD + sipariş numarası üretimi (`BND-YYYY-NNNNN`)
3. Adres kaydı + **beş katmanlı doğrulama**:
   - geofence (`ST_Contains` Bandırma poligonu)
   - yola snap (`ways_vertices_pgr`'da en yakın düğüm + eşik kontrolü)
   - ters geocoding ile adres metni onayı
   - aykırı değer uyarısı
   - firma onay/ret akışı + durum makinesi
4. `DistanceMatrixProvider` + kendi Dijkstra'mız + `distance_matrix_cache`

**Python**
5. Graf yükleyici + `dijkstra.py`
6. Basit TSP: tek araç, durak sıralama (nearest neighbor ile başla)
7. `POST /optimize` ilk sürüm

**Frontend**
8. F1, F3, F4, F5 ekranları + harita ile konum seçimi
9. Rota haritada çizimi

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
1. `vrp/model.py` — Solution, Route, Stop veri yapıları
2. `objective.py` — amaç fonksiyonu (mesafe + yakıt + sürücü + öncelik cezası + araç sabit)
3. `feasibility.py` — kapasite ve (sonraki fazda) süre kontrolleri
4. `construction/savings.py` — Clarke-Wright, kapasite farkında
5. `localsearch/` — 2-opt, relocate, exchange, Or-opt
6. `metaheuristic/simulated_annealing.py` — tohumlanmış, sıcak başlangıçlı
7. `routewise.py` — aşamaları birleştir
8. `construction/naive.py` — baseline, her koşuda birlikte hesaplanır
9. Backend: `optimization_runs` + `routes` + `route_stops` kalıcılaştırma
10. Frontend: F6, F7 — planlama ekranı, maliyet kırılımı

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
1. `feasibility.py`'ye maksimum rota süresi kısıtını ekle
2. Süre hesabı: segment uzunluğu / `maxspeed` + servis süresi
3. ETA hesabı ve `route_stops.eta` doldurma
4. Bildirim altyapısı: uygulama içi + Spring Mail
5. Rol bazlı yetkilendirme tüm endpoint'lerde
6. Kurye ekranları K1–K4
7. Alıcı ekranları A1–A5

### Biten iş tanımı
- [ ] Gün sınırını aşan çözüm **üretilmiyor**
- [ ] ETA'lar makul (elle birkaç durak için doğrula)
- [ ] Alıcı e-posta ve uygulama içi bildirim alıyor
- [ ] **Rol sızıntısı testleri yeşil** — alıcı başkasının teslimatını göremiyor
- [ ] Kurye yalnızca kendi rotasını görüyor

---

## Faz 5 — Katman 4: Dinamik Yeniden Rotalama (2 hafta)

### Amaç
Dağıtım sırasında erteleme ve adres değişikliği rotayı canlı olarak güncelliyor.

### Görevler
1. "Başka gün gelsin" → `ERTELENDİ` + kümeden çıkarma + yeniden optimizasyon
2. Dağıtım sırasında adres değişikliği → doğrulamaya düşme → onay sonrası yeniden rotalama
3. **Son tamamlanan duraktan** yeniden optimizasyon (canlı GPS yok)
4. Beş güvenlik önlemi:
   - olurluluk kırılırsa otomatik erteleme
   - `YOLDA` durumunda adres kilidi
   - maliyet artışı eşiği → erteleme önerisi
   - günlük değişiklik limiti
   - kuryeye açık bildirim
5. Debounce (~500 ms) + asenkron koşu + ilerleme göstergesi

### Biten iş tanımı
- [ ] Erteleme sonrası rota yeniden hesaplanıyor, teslim edilenler sabit kalıyor
- [ ] Adres değişikliği gün sınırını aşırırsa teslimat **otomatik erteleniyor**
- [ ] `YOLDA` teslimatın adresi değiştirilemiyor
- [ ] Günlük değişiklik limiti devrede
- [ ] Kurye ekranında eski durak kalkıyor, bildirim düşüyor
- [ ] Arka arkaya 10 değişiklikte 10 değil 1 optimizasyon koşuyor

---

## Faz 6 — Katman 5: Karşılaştırma ve Analiz (2,5 hafta)

### Amaç
Projenin en görünür çıktısı: algoritma karşılaştırması, tasarruf analizi, raporlar.

### Görevler

**Seviye 1 — yol bulma**
1. `astar.py`, `bfs.py`, `dfs.py` implementasyonları
2. Ortak metrik toplama: yol uzunluğu, süre (ms), gezilen düğüm
3. `path_comparison_runs` kayıtları
4. `POST /compare/path`

**Seviye 2 — tur sırası**
5. `nearest_neighbor.py`, `exhaustive.py` (n ≤ 10)
6. `POST /compare/tour` — tüm algoritmaları aynı girdiyle koşturur
7. Her algoritma N kez, medyan raporlanır

**Analiz**
8. `aggregate.py` — haftalık/aylık/yıllık pandas resample
9. `significance.py` — Wilcoxon / paired t-test
10. `figures.py` — matplotlib figürleri (prototip: `docs/diagrams/cost_comparison.py`)
11. `export.py` — Excel (5 sayfa) + PDF rapor
12. Frontend: F8, F9 — karşılaştırma tabloları, Recharts grafikleri, indirme

### Biten iş tanımı
- [ ] BFS'in ağırlıkları yok saydığı **sayısal olarak gösteriliyor** (Dijkstra'dan sapma %)
- [ ] A\*, Dijkstra ile aynı yolu daha az düğüm gezerek buluyor
- [ ] DFS-exhaustive küçük örnekte gerçek optimumu veriyor
- [ ] "RouteWise optimumun %X içinde" cümlesi veriyle destekleniyor
- [ ] En az **30 senaryoda** anlamlılık testi koşuldu, p değeri raporlanıyor
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
