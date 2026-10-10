# Sistem Mimarisi

> Durum: taslak — 2026-10-10. Teknoloji ve kapsam kararları alındı, modül tasarımı sürüyor.

## Problem Tanımı

Çözülen problem: **MDVRPTW** — Multi-Depot Vehicle Routing Problem with Time Windows.

| Kısıt / boyut | Durum |
|---|---|
| Araç kapasitesi | sert kısıt |
| Teslimat zaman penceresi | sert kısıt (olurluluk) |
| Çok depo | araçlar farklı merkezlere bağlı; teslimat-depo ataması optimizasyonun parçası |
| Teslimat önceliği | amaç fonksiyonunda ceza katsayısı |
| Yakıt + sürücü maliyeti | amaç fonksiyonunda |

Bu problem sınıfı NP-zor ve literatürde aktif araştırma konusudur. Optimal çözüm
garantisi hedeflenmez; metasezgisel yaklaşımla iyi çözüm üretilir ve kalitesi
yayınlanmış benchmark'lara karşı ölçülür (bkz. Doğrulama Stratejisi).

## Katmanlı İnşa Planı

Sistem tek hamlede MDVRPTW olarak yazılmaz. Her katman **çalışan ve demo edilebilir**
bir sistemdir:

| Katman | Kapsam | Çıktı |
|---|---|---|
| 1 | CVRP — kapasite + maliyet, tek depo, pencere yok | çalışan sistem |
| 2 | + zaman penceresi (VRPTW) | çalışan sistem |
| 3 | + çok depo (MDVRPTW) | hedef kapsam |

Gerekçe: takvim sıkışırsa son çalışan katman sunulur. Tek parça geliştirmede proje
bitmezse ortada gösterilebilir hiçbir şey kalmaz.

## Genel Yapı

Kritik tasarım kuralı: **optimizasyon servisi durumsuzdur (stateless) ve veritabanına
erişmez.** Tüm veri sahipliği backend'de toplanır.

```
┌──────────────┐        ┌─────────────────────┐        ┌──────────────────┐
│  Frontend    │  REST  │   Backend (API)     │  REST  │   Optimizer      │
│  React+Vite  │ ─────► │   Spring Boot       │ ─────► │   FastAPI        │
│  MapLibre    │        │   Java 21           │ ◄───── │   (stateless)    │
└──────────────┘        └──────┬──────────────┘        └──────────────────┘
                               │                   mesafe+süre matrisi,
                        ┌──────▼──────────┐        araçlar, kısıtlar →
                        │ PostgreSQL      │        ← rotalar + metrikler
                        │ + PostGIS       │
                        └─────────────────┘
                               ▲
                               │ matris cache
                        ┌──────┴──────────────────┐
                        │ DistanceMatrixProvider  │
                        │ → OpenRouteService API  │
                        │ → Euclidean (benchmark) │
                        │ → (OSRM: ileride)       │
                        └─────────────────────────┘
```

## Bileşen Sorumlulukları

### Backend — Spring Boot 3.4+ / Java 21 (`backend/`)

Veri ve iş akışının tek sahibi.

- Kimlik doğrulama ve yetkilendirme
- Domain yönetimi: kullanıcı, depo, araç/kurye, teslimat noktası, teslimat, rota
- Mesafe **ve süre** matrisinin tedariki ve cache'lenmesi
- Optimizer servisinin çağrılması, sonucun kalıcılaştırılması
- Haftalık / aylık / yıllık tasarruf analizleri (SQL agregasyonları)

Teknolojiler: Gradle, Spring Web, Spring Data JPA, Liquibase, Hibernate Spatial.

### Optimizer — Python 3.12 / FastAPI (`optimizer/`)

Saf hesaplama servisi. **DB yok, migration yok, kalıcı durum yok.**

| Endpoint | Girdi | Çıktı |
|---|---|---|
| `POST /optimize` | matrisler, depolar, araçlar, teslimatlar, kısıtlar, maliyet parametreleri | optimize rotalar + metrikler |
| `POST /baseline` | aynı girdi | naive rota (karşılaştırma referansı) + metrikler |

Dış bağımlılığı sıfır olduğu için algoritma testleri sentetik matrislerle ve benchmark
dosyalarıyla, tam izole şekilde yazılabilir.

### Frontend — React 19 + Vite + TypeScript (`frontend/`)

- Depo, teslimat ve araç yönetimi arayüzü
- Rota görselleştirme (MapLibre GL)
- Optimize rota ile standart rota karşılaştırma tablosu
- Analiz ve raporlama ekranları

### Veritabanı — PostgreSQL + PostGIS

Coğrafi sorgular, dağıtım geçmişi, mesafe/süre matrisi cache'i.

## Optimizasyon Algoritması

Hazır çözücü (OR-Tools vb.) kullanılmaz; algoritma projeye özgü olarak geliştirilir.

| Aşama | Yöntem | Gerekçe |
|---|---|---|
| Depo ataması | Cluster-first: en yakın olurlu depo; yerel aramada depo değiştirme hamlesi | MDVRP'nin ek katmanı |
| Başlangıç çözümü | **Solomon I1 insertion** heuristiği | Zaman penceresini doğası gereği yönetir; VRPTW'nin standart yapı taşı |
| Yerel arama | **relocate, exchange, Or-opt, 2-opt\*** | Hiçbiri segment ters çevirmez → zaman olurluluğu korunur |
| Metasezgisel | Simulated annealing (alternatif: tabu search) | Yerel optimumdan kaçış |

### Neden savings ve 2-opt değil

İlk taslakta Clarke-Wright savings + 2-opt önerilmişti; zaman penceresi kapsama
girdiğinde ikisi de uygun değil:

- **Savings**, tek depolu ve zaman penceresiz CVRP için tasarlanmıştır; pencere
  olurluluğunu birleştirme adımında değerlendiremez.
- **2-opt** bir rota segmentini ters çevirir. Ters çevirme segment boyunca tüm varış
  saatlerini kaydırdığı için VRPTW'de çoğu hamle olursuz çözüm üretir. Yerine segment
  ters çevirmeyen hamleler (Or-opt, relocate, exchange) ve rotalar arası 2-opt\*
  kullanılır.

## Maliyet Modeli (Amaç Fonksiyonu)

Maliyet, rota hesaplandıktan **sonra** eklenen bir rapor alanı değildir; amaç
fonksiyonunun parçasıdır. Projenin özgün yanının kodda karşılığı burasıdır.

| Değişken | Kaynak | Rol |
|---|---|---|
| Toplam mesafe | mesafe matrisi | maliyet bileşeni |
| Toplam süre | süre matrisi | maliyet bileşeni |
| Yakıt tüketimi | araç bazlı L/100km × mesafe | maliyet bileşeni |
| Yakıt maliyeti | tüketim × yakıt birim fiyatı | maliyet bileşeni |
| Sürücü maliyeti | süre × saatlik maliyet | maliyet bileşeni |
| Bekleme süresi | pencere açılışından önce varış | maliyet bileşeni |
| Öncelik ihlali | teslimat önceliği × ceza katsayısı | ceza |
| Araç kapasitesi | araç tanımı | **sert kısıt** |
| Zaman penceresi | teslimat tanımı | **sert kısıt** |

Parametreler backend'de saklanır, her optimizasyon çağrısında Optimizer'a aktarılır.

## Optimizasyon Akışı

1. Frontend: "şu teslimat kümesini şu araçlarla optimize et"
2. Backend: teslimatları, araçları ve depoları DB'den yükler, konum listesini kurar
3. Backend: mesafe **ve süre** matrisini cache'ten okur; yoksa `DistanceMatrixProvider`
   ile çeker ve cache'ler
4. Backend: matrisler + kısıtlar + maliyet parametrelerini Optimizer'a POST eder
5. Optimizer: optimize rotayı **ve** naive baseline'ı hesaplar, ikisinin metriklerini döner
6. Backend: rotayı ve karşılaştırma metriklerini kalıcılaştırır
7. Frontend: haritada çizer, tasarruf farkını gösterir

Her koşuda baseline'ın da hesaplanıp kaydedilmesi, "optimize rota ile standart rota
arasındaki fark ölçülebilsin" başarı kriterini veri seviyesinde karşılar; tasarruf
analizleri bu kayıtlar üzerinden türetilir.

## Çalışma Modları

Sistem iki ayrı veri modunda koşar. İkisi farklı soruyu cevaplar:

| Mod | Veri kaynağı | Mesafe | Cevapladığı soru |
|---|---|---|---|
| **Benchmark** | Solomon / Cordeau örnek dosyaları | Öklid (dosyadaki koordinatlardan) | "Algoritmam ne kadar iyi?" |
| **Gerçek** | Türkiye adresleri, ORS API | gerçek yol ağı | "Pratikte uygulanabilir mi?" |

Benchmark modu internet veya API gerektirmez — örnek dosyaları kendi koordinatlarını
taşır ve literatürde Öklid mesafesiyle tanımlıdır.

## Mesafe Verisi Kararı

**Seçim: OpenRouteService API** (ücretsiz katman), gerçek mod için; iki zorunlu önlemle:

1. **Matris cache'i (DB)** — metasezgisel algoritma matrisi koşu başına bir kez ister,
   iterasyon başına değil. Aynı nokta kümesi → cache hit. Algoritma ayarı sırasındaki
   yüzlerce koşunun kotayı yakmasını bu engeller.
2. **`DistanceMatrixProvider` arayüzü** — ORS, Öklid (benchmark) ve ileride OSRM
   implementasyonları aynı arayüz arkasında. Sağlayıcı değişimi algoritmayı etkilemez.

Zaman penceresi kapsama dahil olduğu için **süre matrisi de zorunlu**; ORS mesafe ve
süreyi tek çağrıda döner.

Bilinen sınır: ORS ücretsiz katmanda tek matris isteği için `kaynak × hedef ≤ 3500`
kısıtı olduğu biliniyor (≈59 konum). Çok depo modeliyle konum sayısına depolar da
eklendiği için bu sınır daraldı. **Kayıt sırasında resmî dokümandan teyit edilecek.**
Sınıra dayanılırsa matris parçalanıp birleştirilir veya OSRM'e geçilir.

OSRM neden şimdilik seçilmedi: self-hosted kurulumu tek seferlik ama gerçek bir maliyet
(OSM dosyası indirme, `osrm-extract`/`partition`/`customize` ön işleme, birkaç GB RAM,
~5-10GB disk). Geçiş yolu açık bırakılarak erteleniyor; demo ortamında internet
bağımlılığına karşı yedek plan olarak elde tutulur.

## Doğrulama Stratejisi

Kendi algoritması yazılan bir projede "çözümüm ne kadar iyi?" sorusunun naive baseline'a
karşı verilen cevabı zayıftır (naive zaten kötüdür). Gerçek cevap literatürdeki standart
test kümeleridir:

| Küme | Kapsam | Kullanım |
|---|---|---|
| **Solomon** | VRPTW, tek depo | Katman 2 doğrulaması |
| **Cordeau** | MDVRPTW, çok depo | Katman 3 doğrulaması |

İkisi de herkese açık ve en iyi bilinen çözümleri yayınlanmıştır. Hedef metrik: en iyi
bilinen çözüme göre **yüzde sapma (gap %)**. Bu, raporun en güçlü nicel iddiasıdır.

## Test Stratejisi

| Katman | Yaklaşım |
|---|---|
| Optimizer — olurluluk | kapasite ve pencere ihlali **asla** üretilmediğinin testi (her hamle için) |
| Optimizer — algoritma | sentetik matrisler; bilinen optimal çözümü olan küçük örnekler |
| Optimizer — kalite | benchmark örnekleri üzerinde gap % regresyon testi |
| Backend servis | birim test + mock'lanmış Optimizer istemcisi |
| Backend repository | Testcontainers ile gerçek PostgreSQL+PostGIS (H2 **değil**) |
| Backend REST | slice/controller testleri |
| Frontend | component testleri; form'lar için valid submit + validation + async hata |

En kritik test sınıfı ilk satırdır: zaman penceresi ve kapasite **sert kısıt**, dolayısıyla
hiçbir yerel arama hamlesi olursuz çözüm üretmemelidir. Bu testler olmadan algoritma
sessizce geçersiz rotalar döndürür.

## Açık Kararlar

- [ ] Kimlik doğrulama yöntemi (JWT / session)
- [ ] Metasezgisel seçimi: simulated annealing mi tabu search mü (katman 2'de ölçülecek)
- [ ] Gerçek mod için hangi şehir / veri kümesi
- [ ] Zaman penceresi ihlali: tamamen sert kısıt mı, yoksa yüksek cezalı yumuşak kısıt mı
- [ ] Deployment hedefi (yerel Docker / bulut)
