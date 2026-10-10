# Sistem Mimarisi

> Durum: 2026-10-10. Kapsam ve teknoloji kararları alındı. Henüz kod yazılmadı.

## 1. Problem Tanımı

**Kapsam: Bandırma içi kargo dağıtımı, tek dağıtım merkezi.**

Çözülen problem: **CVRP + maksimum rota süresi** (Capacitated Vehicle Routing Problem,
tek depo, çalışma günü süre sınırı ile).

| Boyut | Modelleme |
|---|---|
| Dağıtım merkezi | **tek** depo — kargo şirketinin Bandırma şubesi, tüm araçların çıkış ve dönüş noktası |
| Araç kapasitesi | **sert kısıt** — aşılamaz |
| Çalışma günü süresi | **sert kısıt** — her rota gün içinde tamamlanmalı (maksimum rota süresi) |
| Teslimat önceliği | amaç fonksiyonunda ceza katsayısı |
| Yakıt + sürücü maliyeti | amaç fonksiyonunda |
| Müşteri bazlı zaman penceresi | **kapsam dışı** (bkz. aşağıdaki not) |

### Neden zaman penceresi yok

"Tüm teslimatları gün içinde tamamla" ile "bu paket 09:00–12:00 arası teslim edilmeli"
farklı iki kısıttır. Birincisi rota başına tek bir üst sınırdır ve toplam süreyi kontrol
etmek yeterlidir. İkincisi (VRPTW) her teslimat için ayrı olurluluk kontrolü, varış saati
yayılımı ve bekleme süresi hesabı gerektirir — problem sınıfını değiştirir.

Proje tanımı birincisini tarif ediyor, dolayısıyla kapsam buna göre sabitlendi.

## 2. Katmanlı İnşa Planı

Her katman **çalışan ve demo edilebilir** bir sistemdir. Takvim sıkışırsa son çalışan
katman sunulur.

| Katman | Kapsam |
|---|---|
| 1 | Kargo listesi yönetimi + tek araç + mesafe tabanlı rota (TSP) |
| 2 | Çok araç + kapasite kısıtı + maliyet modeli (CVRP) |
| 3 | Maksimum rota süresi kısıtı + ETA hesabı + bildirim |
| 4 | Algoritma karşılaştırma modülü + istatistik raporları |

Karşılaştırma modülü (Katman 4) projenin en görünür çıktısı olduğu için, ölçüm altyapısı
Katman 2'den itibaren hazır tutulur — her koşunun metrikleri baştan kaydedilir.

## 3. Bileşenler

Kritik tasarım kuralı: **Optimizer servisi iş verisi açısından durumsuzdur ve
veritabanına erişmez.** Teslimat, araç ve rota verisi her zaman istekle birlikte gelir.
Tek istisna yol grafıdır: değişmeyen referans veri olarak servis açılışında bir kez
dosyadan yüklenir.

```
┌────────────────┐         ┌──────────────────────┐         ┌────────────────────┐
│   Frontend     │  REST   │   Backend (API)      │  REST   │    Optimizer       │
│                │ ──────► │                      │ ──────► │                    │
│ React 19       │         │ Spring Boot 3.4+     │ ◄────── │ Python 3.12        │
│ Vite + TS      │         │ Java 21              │         │ FastAPI            │
│ MapLibre GL    │         │                      │         │                    │
└────────────────┘         └─────────┬────────────┘         └─────────┬──────────┘
                                     │                                │
                                     │                      yol grafı │ (açılışta,
                           ┌─────────▼──────────┐           dosyadan  │  bir kez)
                           │   PostgreSQL       │                     │
                           │   + PostGIS        │ ────────────────────┘
                           │   + pgRouting      │
                           └─────────▲──────────┘
                                     │ bir kez: osm2pgrouting
                           ┌─────────┴──────────┐
                           │ Bandırma OSM verisi│
                           └────────────────────┘
```

### Backend — Spring Boot 3.4+ / Java 21 (`backend/`)

Veri ve iş akışının tek sahibi.

- Kimlik doğrulama ve yetkilendirme
- Domain: depo, araç, kargo/teslimat, adres, rota, koşu kaydı
- **Kargo listesi yönetimi** — manuel paket ekleme/çıkarma
- Liste değiştiğinde yeniden optimizasyon tetikleme
- **ETA hesabı** ve alıcıya bildirim (uygulama içi + e-posta)
- Algoritma karşılaştırma koşularının yönetimi ve metriklerin kalıcılaştırılması
- Haftalık / aylık / yıllık tasarruf analizleri (SQL agregasyonları)

Teknolojiler: Gradle, Spring Web, Spring Data JPA, Liquibase, Hibernate Spatial, Spring Mail.

### Optimizer — Python 3.12 / FastAPI (`optimizer/`)

Saf hesaplama servisi. Hem RouteWise algoritmasını hem karşılaştırma algoritmalarının
tamamını barındırır.

| Endpoint | İş |
|---|---|
| `POST /optimize` | RouteWise algoritmasıyla optimize rota |
| `POST /compare/tour` | Seviye 2: tüm tur algoritmalarını aynı girdiyle koşturur, metrikleri döner |
| `POST /compare/path` | Seviye 1: tüm yol bulma algoritmalarını koşturur, metrikleri döner |
| `GET /health` | yol grafının yüklendiğini doğrular |

### Frontend — React 19 + Vite + TypeScript (`frontend/`)

- Kargo listesi ekranı: paket ekleme/çıkarma, adres girişi
- Rota görselleştirme (MapLibre GL) — depo, duraklar, çizilen rota
- Algoritma karşılaştırma ekranı: tablolar ve grafikler
- Tasarruf analizi raporları
- ETA ve bildirim görünümü

### Veritabanı — PostgreSQL + PostGIS + pgRouting

- İş verisi: depo, araç, kargo, adres, rota, koşu metrikleri
- Coğrafi sorgular (PostGIS)
- **Bandırma yol grafı** (pgRouting tabloları) — `osm2pgrouting` ile bir kez yüklenir

## 4. Yol Grafı ve Mesafe Verisi

### Karar: kendi yol grafımız, harici API yok

Bandırma OSM verisi `osm2pgrouting` ile PostGIS'e yüklenir ve `ways` /
`ways_vertices_pgr` tablolarında ağırlıklı yol grafı oluşur. Mesafe ve süre matrisi
**kendi Dijkstra implementasyonumuzla** bu graf üzerinden hesaplanır.

Bunun sonucu, önceki taslaktaki OpenRouteService bağımlılığının **tamamen ortadan
kalkmasıdır**:

| Önceki taslaktaki sorun | Yol grafıyla durum |
|---|---|
| ORS ücretsiz katman kotası | yok — sınırsız sorgu |
| Tek matris isteğinde ≈59 konum sınırı | yok — konum sayısı serbest |
| API key yönetimi | yok |
| Demo gününde internet bağımlılığı | yok — tamamen yerel çalışır |

Bu kararı mümkün kılan şey kapsamın Bandırma'ya daralmasıdır: Türkiye geneli OSM verisi
~400MB ve GB'larca RAM gerektirirken, Bandırma çıkartması birkaç on MB mertebesindedir.

Ayrıca **Seviye 1 algoritma karşılaştırması ancak gerçek grafla mümkündür** — hazır bir
matris API'si grafı vermediği için Dijkstra/A\*/BFS/DFS karşılaştırılamazdı.

### Süre hesabı

OSM verisi yol parçası başına `maxspeed` taşır. Süre, segment uzunluğu ve hız
sınırından hesaplanır; hız bilgisi olmayan segmentler için yol tipine göre varsayılan
hız atanır. Buna durak başına **servis süresi** (indirme/teslim) eklenir.

### Doğrulama

pgRouting'in kendi `pgr_dijkstra` fonksiyonu, bizim Dijkstra implementasyonumuz için
**doğruluk referansı** olarak kullanılır: aynı girdide aynı mesafeyi vermeli. Opsiyonel
ikinci çapraz kontrol olarak birkaç rota ORS veya Google Maps ile tek seferlik
karşılaştırılabilir (kapsam zorunluluğu değil).

## 5. RouteWise Algoritması

Hazır çözücü (OR-Tools vb.) kullanılmaz; algoritma projeye özgü geliştirilir.

| Aşama | Yöntem |
|---|---|
| Başlangıç çözümü | **Clarke-Wright savings** (kapasite ve süre farkında) |
| Yerel arama — rota içi | **2-opt** |
| Yerel arama — rotalar arası | **relocate, exchange, Or-opt** |
| Metasezgisel | **Simulated annealing** |
| Olurluluk | her hamlede kapasite ve maksimum rota süresi kontrol edilir |

### Önceki taslaktan dönüş: savings ve 2-opt geri geldi

Önceki taslakta kapsam MDVRPTW olduğu için Clarke-Wright savings ve 2-opt reddedilmiş,
yerine Solomon I1 insertion önerilmişti. Gerekçe zaman penceresiydi: 2-opt bir rota
segmentini ters çevirir, ters çevirme varış saatlerini kaydırır ve pencereleri ihlal eder.

Zaman penceresi kapsamdan çıkınca bu gerekçe ortadan kalktı:

- **Savings** tek depolu, pencere-siz CVRP için tasarlanmıştır — kapsam artık tam olarak bu.
- **2-opt** güvenli: simetrik matriste segment ters çevirme toplam mesafeyi azaltıyorsa
  süreyi de azaltır, dolayısıyla maksimum süre kısıtı basit bir toplam kontrolüyle korunur.
  Zaman penceresindeki varış saati yayılımı problemi yok.

Sonuç: literatürde en iyi dokümante edilmiş, en çok örneği olan klasik kurulum
kullanılabiliyor. Bu doğrudan risk azalmasıdır.

## 6. Amaç Fonksiyonu (Maliyet Modeli)

Maliyet, rota hesaplandıktan **sonra** eklenen bir rapor alanı değildir; amaç
fonksiyonunun parçasıdır. Projenin özgün yanının kodda karşılığı burasıdır.

| Değişken | Kaynak | Rol |
|---|---|---|
| Toplam mesafe | yol grafı + Dijkstra | maliyet bileşeni |
| Toplam süre | segment uzunluğu / hız sınırı + servis süresi | maliyet bileşeni |
| Yakıt tüketimi | araç bazlı L/100km × mesafe | maliyet bileşeni |
| Yakıt maliyeti | tüketim × yakıt birim fiyatı | maliyet bileşeni |
| Sürücü maliyeti | süre × saatlik maliyet | maliyet bileşeni |
| Öncelik ihlali | teslimat önceliği × ceza katsayısı | ceza |
| Araç kapasitesi | araç tanımı | **sert kısıt** |
| Maksimum rota süresi | çalışma günü tanımı | **sert kısıt** |

Parametreler backend'de saklanır, her optimizasyon çağrısında Optimizer'a aktarılır.
Böylece yakıt fiyatı veya sürücü maliyeti değiştiğinde kod değişmeden sonuçlar değişir.

## 7. Algoritma Karşılaştırması

Projenin en görünür çıktısı. **İki ayrı karar seviyesi** vardır ve algoritmalar
karıştırılmamalıdır.

### Seviye 1 — Yol bulma: "A'dan B'ye hangi yoldan?"

Bandırma yol grafı üzerinde, iki nokta arasında.

| Algoritma | Beklenen davranış |
|---|---|
| **Dijkstra** | Ağırlıklı grafta en kısa yolu garanti eder — referans optimum |
| **A\*** | Dijkstra ile aynı sonucu bulur, haversine sezgiseli sayesinde daha az düğüm gezer |
| **BFS** | **Kenar ağırlıklarını yok sayar** → en az kavşaklı yolu bulur, en kısa yolu değil |
| **DFS** | Optimallik garantisi yok; keşif sırasına bağlı, genelde çok kötü yol |

BFS'in buradaki yanlışlığı rapor açısından **değerli bir bulgudur**: yol ağı ağırlıklı bir
graftır, dolayısıyla ağırlıkları yok sayan bir arama algoritması doğru cevabı veremez.
Bunu ölçüp sayısallaştırmak, algoritma seçiminin neden önemli olduğunu gösterir.

Ölçülen metrikler: yol uzunluğu (m), hesaplama süresi (ms), gezilen düğüm sayısı,
Dijkstra optimumundan sapma (%).

### Seviye 2 — Tur sırası: "Hangi teslimat hangi sırayla, hangi araçla?"

| Algoritma | Rolü |
|---|---|
| **Naive** (giriş sırası) | Taban çizgi — insan dispatcher davranışı |
| **Nearest Neighbor (greedy)** | Klasik açgözlü kurucu heuristik |
| **Clarke-Wright savings** | Klasik kurucu heuristik, RouteWise'ın başlangıç adımı |
| **RouteWise** (savings + yerel arama + SA) | Projenin kendi algoritması |
| **DFS-exhaustive** (yalnızca n ≤ 10) | Tam arama → **gerçek optimum** |

DFS burada meşrudur: DFS + backtracking tüm permütasyonları gezer, küçük örneklerde
gerçek optimumu bulur. Bu, "RouteWise gerçek optimumun %X içinde" diyebilmeyi sağlar —
raporun en güçlü nicel iddiası.

Ölçülen metrikler: toplam mesafe, toplam süre, yakıt (L), maliyet (TL), kullanılan araç
sayısı, hesaplama süresi (ms), optimumdan/en iyi bilinenden sapma (%).

### Yöntem notu: hesaplama süresi karşılaştırması

"Hangi algoritma daha hızlı" ölçümü ancak implementasyonlar benzer özenle yazıldığında
adildir; aynı dilde (Python), aynı veri yapıları üzerinde, aynı koşulda ölçülür. Her
algoritma aynı girdi üzerinde N kez koşturulur, medyan raporlanır.

Daha önemlisi şu: asıl ilginç sonuç tek başına hız değil, **kalite–süre dengesi**.
Greedy çok hızlı ama kötü; DFS-exhaustive optimal ama kullanılamayacak kadar yavaş;
RouteWise ikisinin arasında bir yerde durur. Raporun ana grafiği bu dengeyi gösterir.

## 8. Akışlar

### Optimizasyon akışı

1. Kullanıcı kargo listesine paket ekler veya çıkarır
2. Backend: teslimatları, araçları ve depoyu DB'den yükler
3. Backend: mesafe/süre matrisini cache'ten okur; yoksa yol grafı üzerinde Dijkstra ile
   hesaplar ve cache'ler
4. Backend: matrisler + kısıtlar + maliyet parametrelerini Optimizer'a POST eder
5. Optimizer: RouteWise ile optimize rotayı **ve** naive baseline'ı hesaplar
6. Backend: rotayı, metrikleri ve karşılaştırmayı kalıcılaştırır
7. Backend: durak bazında ETA hesaplar, alıcılara bildirim gönderir
8. Frontend: haritada rotayı çizer, tasarruf farkını gösterir

### ETA hesabı

```
ETA(durak i) = depodan çıkış saati
             + Σ ( seyahat süresi + servis süresi )   [1..i arası duraklar]
```

Seyahat süresi yol grafındaki segment uzunlukları ve hız sınırlarından gelir.
Alıcıya uygulama içi bildirim ve e-posta ile iletilir.

### Karşılaştırma koşusu

Karşılaştırma, normal optimizasyon akışından **ayrı** tetiklenir (her paket eklemede
tüm algoritmaları koşturmak gereksiz pahalıdır). Kullanıcı bir kargo listesi seçer,
"karşılaştırma koşusu başlat" der; sonuçlar koşu kaydı olarak saklanır ve tablolara
dökülür.

## 9. Test Stratejisi

| Katman | Yaklaşım |
|---|---|
| Optimizer — olurluluk | kapasite ve maksimum süre ihlalinin **asla** üretilmediği testi, her yerel arama hamlesi için |
| Optimizer — Dijkstra doğruluğu | pgRouting `pgr_dijkstra` sonucuyla birebir karşılaştırma |
| Optimizer — algoritma kalitesi | küçük örneklerde DFS-exhaustive optimumuna karşı gap % regresyon testi |
| Optimizer — determinizm | SA tohumlanmış (seeded) rastgelelikle tekrarlanabilir olmalı |
| Backend servis | birim test + mock'lanmış Optimizer istemcisi |
| Backend repository | Testcontainers ile gerçek PostgreSQL+PostGIS (H2 **değil**) |
| Backend REST | slice/controller testleri |
| Frontend | component testleri; form'lar için valid submit + validation + async hata |

En kritik test sınıfı ilk satırdır: kapasite ve süre **sert kısıt** olduğundan hiçbir
yerel arama hamlesi olursuz çözüm üretmemelidir. Bu testler olmadan algoritma sessizce
geçersiz rotalar döndürür ve bunu kimse fark etmez.

## 10. Açık Kararlar

- [ ] **Adres → koordinat**: Nominatim ile adres arama mı, haritadan tıklama mı, ikisi mi?
- [ ] **Yeniden optimizasyon tetiklemesi**: her paket değişiminde anında mı (SA saniyeler
      sürer — asenkron + ilerleme göstergesi gerekir), yoksa debounce/açık buton mu?
- [ ] Kimlik doğrulama yöntemi (JWT / session)
- [ ] Araç filosu sabit mi, kullanıcı tanımlı mı?
- [ ] Bandırma OSM çıkartmasının sınırları (bbox) ve kaynağı
- [ ] Deployment hedefi (yerel Docker / bulut)
