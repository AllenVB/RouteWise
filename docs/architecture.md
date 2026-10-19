# Sistem Mimarisi

> Durum: 2026-10-10. Kapsam, roller ve teknoloji kararları alındı. Henüz kod yazılmadı.

## 1. Problem Tanımı

**Kapsam: Bandırma içi kargo dağıtımı, tek dağıtım merkezi.**

Çözülen problem: **CVRP + maksimum rota süresi** (Capacitated Vehicle Routing Problem,
tek depo, çalışma günü süre sınırı ile), **dağıtım sırasında dinamik yeniden rotalama** ile.

| Boyut | Modelleme |
|---|---|
| Dağıtım merkezi | **tek** depo — kargo şirketinin Bandırma şubesi |
| Araç kapasitesi | **sert kısıt** |
| Çalışma günü süresi | **sert kısıt** (maksimum rota süresi) |
| Teslimat önceliği | amaç fonksiyonunda ceza katsayısı |
| Yakıt + sürücü maliyeti | amaç fonksiyonunda |
| Müşteri bazlı zaman penceresi | **kapsam dışı** |

"Tüm teslimatlar gün içinde bitsin" rota başına tek bir süre üst sınırıdır; müşteri bazlı
zaman penceresi (VRPTW) değildir. İkincisi her teslimat için ayrı olurluluk kontrolü,
varış saati yayılımı ve bekleme süresi hesabı gerektirir ve problem sınıfını değiştirir.

## 2. Roller ve Yetkilendirme

Üç rol. Kimlik doğrulama: **JWT**.

| Yetki | Kargo Firması | Kurye | Alıcı |
|---|---|---|---|
| Kargo kaydı oluşturma | ✓ | — | — |
| Adres doğrulama / reddetme | ✓ | — | — |
| Rota oluşturma, optimizasyon | ✓ | — | — |
| Rotayı görme | tümü | kendi rotası | kendi teslimatı |
| Teslimat durumu güncelleme | ✓ | kendi durakları | — |
| Kendi adresini değiştirme | ✓ | — | ✓ |
| Teslimatı erteleme ("başka gün") | ✓ | — | ✓ |
| Karşılaştırma / analiz raporları | ✓ | — | — |

Firma, alıcıların koordinat ve adreslerini doğrulayan merci konumundadır; doğrulanmamış
hiçbir adres rotaya giremez.

## 3. Teslimat Yaşam Döngüsü ve Adres Doğrulama

### Sipariş numarası

**Sistem üretir** (örn. `BND-2026-00417`). Firma kargo kaydı açar, numara otomatik atanır.
Elle numara girişi olmadığı için yanlış yazma senaryosu baştan ortadan kalkar.

### Adres durum makinesi

```
TASLAK ──► DOĞRULAMA_BEKLİYOR ──► DOĞRULANDI ──► (rotaya girer)
                   ▲                    │
                   └────────────────────┘
              alıcı adresi değiştirdiğinde
              otomatik olarak geri düşer
```

**Yalnızca `DOĞRULANDI` durumundaki adresler optimizasyona girer.** Alıcı adresini
değiştirdiğinde durum otomatik olarak `DOĞRULAMA_BEKLİYOR`'a döner — aksi hâlde firma
doğruladığını sanırken başka bir adrese rota çizilmiş olur.

### Yanlış işaretlemeye karşı katmanlı önlem

Harita üzerinden konum seçiminde tek kontrol yetmez:

| # | Kontrol | Yakaladığı hata |
|---|---|---|
| 1 | **Geofence** — nokta Bandırma sınır poligonu içinde mi? (PostGIS `ST_Contains`) | Haritayı kaydırıp başka şehre tıklama |
| 2 | **Yola snap** — en yakın yol düğümüne ≤ X metre mi? | Tarla / deniz / bina ortasına tıklama |
| 3 | **Ters geocoding onayı** — tıklanan noktanın adres metnini geri göster | Yanlış sokak / mahalle |
| 4 | **Aykırı değer uyarısı** — diğer teslimatların kümesinden anormal uzak | Sistematik kayma |
| 5 | **Firma doğrulaması** | Son kapı — insan onayı |

2. katman çifte iş görür: rota hesabı için zaten yola snap gerekir, çünkü yol ağının
dışındaki bir noktaya rota çizilemez. Doğrulama bedava gelir.

### Teslimat durumları

```
BEKLEMEDE ──► PLANLANDI ──► YOLDA ──► TESLİM_EDİLDİ
     │             │           │
     └─────────────┴───────────┴──► ERTELENDİ  (alıcı "başka gün gelsin" dedi
                                                 veya olurluluk kırıldı)
```

`ERTELENDİ` olan teslimat günün rota kümesinden çıkarılır ve rota yeniden hesaplanır.

## 4. Dinamik Yeniden Rotalama

Projenin adındaki "dinamik"in karşılığı burasıdır.

### Dağıtıma çıkmadan önce

Tam optimizasyon koşar; en tasarruflu rota seçilir ve kuryeye atanır.

### Dağıtım sırasında

Şu iki olay rotayı yeniden tetikler:

1. **Teslimat ertelenir** (alıcı "başka gün gelsin" der) → durak kümeden çıkar
2. **Alıcı adresini değiştirir** → durak yeni konuma taşınır, adres doğrulamaya düşer

Her iki durumda **teslim edilmiş duraklar sabitlenir**, kalan duraklar yeniden optimize
edilir ve kuryeye bildirilir.

### Kuryenin konumu — canlı GPS gerekmiyor

Yeniden optimizasyon **son tamamlanan duraktan** başlar, canlı GPS konumundan değil.
Kurye her teslimatı tamamlandı işaretlediği için sistem kuryenin nerede olduğunu zaten
bilir. Bu, canlı konum takibi altyapısını gereksiz kılar ve pratikte neredeyse aynı
sonucu verir.

### Zorunlu güvenlik önlemleri

Alıcının dağıtım sırasında adres değiştirebilmesi güçlü bir özellik ama tasarlanmazsa
sistemi kırar:

| Risk | Önlem |
|---|---|
| Yeni adres rotayı maksimum süre sınırının üstüne çıkarır | Olurluluk kırılırsa teslimat **otomatik `ERTELENDİ`'ye** düşer ve alıcı bilgilendirilir |
| Kurye o durağa zaten gitmek üzere / yoldayken rota değişir | Teslimat `YOLDA` olduğunda adres **kilitlenir**; değişiklik yalnızca o ana kadar kabul edilir |
| Kurye zaten o bölgeden geçmiş, yeni nokta çok geride | Yeniden optimizasyon maliyet artışı eşiği aşarsa sistem erteleme önerir |
| Tekrarlayan değişiklikle rota titreşimi | Teslimat başına günlük değişiklik limiti (örn. 2) |
| Kurye sessizce yanlış adrese gider | Kuryeye **açık bildirim**; eski durak listesinden kaldırılır |

### Yeniden optimizasyon tetikleme politikası

Tetikleme **anında** — değişiklik olduğu anda rota yeniden hesaplanır. Üç teknik önlemle:

- **Debounce (~500 ms)** — arka arkaya yapılan değişiklikler tek koşuda toplanır; kullanıcı
  yine anında hisseder ama 10 paket eklenince 10 değil 1 optimizasyon koşar.
- **Sıcak başlangıç (warm start)** — SA sıfırdan değil, **önceki rotadan** başlar. Hem çok
  daha hızlıdır hem de sonuç kararlı kalır.
- **Tohumlanmış rastgelelik (seeded RNG)** — SA stokastiktir; tohumlanmazsa aynı girdi
  farklı rota verir ve harita sebepsiz değişiyor gibi görünür. Kullanıcı güveni için şart.
- **Asenkron iş + ilerleme göstergesi** — SA saniyeler sürer; senkron çalışırsa arayüz donar.

## 5. Katmanlı İnşa Planı

Her katman **çalışan ve demo edilebilir** bir sistemdir. Takvim sıkışırsa son çalışan
katman sunulur.

| Katman | Kapsam |
|---|---|
| 1 | Kargo listesi + adres doğrulama + tek araç rotası (TSP) |
| 2 | Çok araç + kapasite + maliyet modeli (CVRP) |
| 3 | Maksimum rota süresi + ETA + bildirim + roller |
| 4 | Dinamik yeniden rotalama (erteleme, adres değişikliği) |
| 5 | Algoritma karşılaştırma modülü + istatistik raporları |

Ölçüm altyapısı Katman 2'den itibaren hazır tutulur — her koşunun metrikleri baştan
kaydedilir, böylece Katman 5 veri üretmek için beklemez.

## 6. Bileşenler

Kritik tasarım kuralı: **Optimizer servisi iş verisi açısından durumsuzdur ve
veritabanına erişmez.** Tek istisna yol grafıdır: değişmeyen referans veri olarak servis
açılışında bir kez dosyadan yüklenir.

```
┌────────────────┐         ┌──────────────────────┐         ┌────────────────────┐
│   Frontend     │  REST   │   Backend (API)      │  REST   │    Optimizer       │
│ React 19 · TS  │ ──────► │ Spring Boot 3.4+     │ ──────► │ Python 3.12        │
│ Vite·MapLibre  │ ◄────── │ Java 21 · JWT        │ ◄────── │ FastAPI            │
└────────────────┘         └─────────┬────────────┘         └─────────┬──────────┘
                                     │                       yol grafı│ (açılışta)
                           ┌─────────▼──────────┐                     │
                           │ PostgreSQL+PostGIS │ ────────────────────┘
                           │    + pgRouting     │
                           └─────────▲──────────┘
                                     │ bir kez: osm2pgrouting
                           ┌─────────┴──────────┐
                           │ Bandırma OSM verisi│
                           └────────────────────┘
```

**Backend** — veri sahipliği, domain, JWT yetkilendirme, adres doğrulama akışı, matris
cache'i, ETA hesabı, bildirim, karşılaştırma koşuları, tasarruf analizleri.
(Gradle, Spring Web/Security, Spring Data JPA, Liquibase, Hibernate Spatial, Spring Mail.)

**Optimizer** — RouteWise algoritması + tüm karşılaştırma algoritmaları.
Endpoint'ler: `POST /optimize`, `POST /compare/tour`, `POST /compare/path`, `GET /health`.

**Frontend** — kargo listesi, harita ile konum seçimi, rota görselleştirme, karşılaştırma
tabloları, raporlar, rol bazlı ekranlar.

**Veritabanı** — iş verisi, koşu metrikleri, coğrafi sorgular, Bandırma yol grafı,
mesafe/süre matrisi cache'i.

### Araç filosu

**Şimdilik sabit**: filo Liquibase seed verisi olarak tanımlanır, yönetim ekranı yazılmaz.
Önemli nüans — "sabit" demek **kaynak koda gömülü** demek değildir. Filo baştan DB tablosu
olarak modellenir; sadece CRUD arayüzü ertelenir. Koda gömülürse proje önerisindeki
"farklı araç senaryolarında çalıştırılabilmesi" kriteri sonradan karşılanamaz.

## 7. Yol Grafı ve Mesafe Verisi

### Karar: kendi yol grafımız, harici rota API'si yok

Bandırma OSM verisi `osm2pgrouting` ile PostGIS'e yüklenir; mesafe/süre matrisi **kendi
Dijkstra implementasyonumuzla** bu graf üzerinden hesaplanır ve DB'de cache'lenir.

### Matrisi kim hesaplar

Graf **Python servisinin belleğindedir**; dolayısıyla Dijkstra da oradadır. Backend
matrisi kendisi hesaplamaz, Python servisinden ister ve sonucu `distance_matrix_cache`
tablosunda saklar. Böylece graf tek bir yerde durur ve algoritmaların tamamı aynı
serviste toplanır.

> Bu, dokümanın ilk sürümündeki "backend matrisi Dijkstra ile hesaplar" ifadesinin
> düzeltilmiş hâlidir (19.10.2026). Backend'in grafa erişimi yoktur.

Sonuç: API kotası yok, konum sayısı sınırı yok, API key yok, internet bağımlılığı yok.
Bunu mümkün kılan şey kapsamın Bandırma'ya daralmasıdır. Ayrıca **Seviye 1 algoritma
karşılaştırması ancak gerçek grafla mümkündür** — hazır bir matris API'si ham grafı vermez.

### OSM veri kaynağı

Geofabrik Türkiye için alt bölge yayınlamıyor ("No sub regions are defined for this
region"); tek dosya `turkey-latest.osm.pbf`, **619 MB** (08.10.2026 itibarıyla). Bandırma'yı
kendimiz kırpacağız. İki yol:

| Yöntem | Artı | Eksi |
|---|---|---|
| **A — Geofabrik + `osmium extract`** *(tercih)* | Betiklenebilir, sürüm sabitlenebilir, repoda belgelenir → **tekrar üretilebilir** | 619 MB tek seferlik indirme |
| **B — BBBike extract servisi** | Haritada kutu çiz, birkaç MB dosya e-postayla gelir, 2–7 dakika | Elle adım; tez için tekrar üretilebilirliği zayıf |

**A tercih ediliyor**: bitirme projesinde veri hattının danışman/jüri tarafından yeniden
kurulabilmesi önemlidir. `osmium` ayrıca Docker imajı olarak çalıştırılabilir, yerel kurulum
gerekmez. B, ilk prototipi hızlı ayağa kaldırmak için yedek olarak kullanılabilir.

Kırpma sınırı için **bbox yerine Bandırma ilçe sınır poligonu** kullanılır (`osmium
extract --polygon`): bbox dikdörtgeni komşu ilçelerden gereksiz yol ağı taşır. Poligon,
Bandırma'nın OSM idari sınır ilişkisinden üretilir — ilişki kimliği kurulum sırasında
OSM'den teyit edilecek. Aynı poligon §3'teki **geofence kontrolünde** de kullanılır.

### Süre hesabı

OSM her yol parçasında `maxspeed` taşır: süre = segment uzunluğu / hız sınırı. Hız bilgisi
olmayan segmentler için yol tipine göre varsayılan hız atanır. Buna durak başına **servis
süresi** eklenir.

### Doğrulama

pgRouting'in `pgr_dijkstra` fonksiyonu, bizim Dijkstra implementasyonumuzun **doğruluk
referansıdır**: aynı girdi aynı mesafeyi vermelidir. Test stratejisinin zorunlu maddesi.

## 8. RouteWise Algoritması

Hazır çözücü (OR-Tools vb.) kullanılmaz.

| Aşama | Yöntem |
|---|---|
| Başlangıç çözümü | **Clarke-Wright savings** (kapasite ve süre farkında) |
| Yerel arama — rota içi | **2-opt** |
| Yerel arama — rotalar arası | **relocate, exchange, Or-opt** |
| Metasezgisel | **Simulated annealing** (tohumlanmış, sıcak başlangıçlı) |
| Olurluluk | her hamlede kapasite ve maksimum rota süresi kontrol edilir |

Zaman penceresi kapsam dışı olduğu için savings ve 2-opt geçerlidir: simetrik matriste
segment ters çevirme toplam mesafeyi azaltıyorsa süreyi de azaltır, dolayısıyla süre kısıtı
basit bir toplam kontrolüyle korunur. (VRPTW'de 2-opt varış saatlerini kaydırdığı için
kullanılamazdı.)

## 9. Amaç Fonksiyonu

Maliyet, rota hesaplandıktan **sonra** eklenen bir rapor alanı değildir; amaç fonksiyonunun
parçasıdır. Projenin özgün yanının kodda karşılığı burasıdır.

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

Parametreler backend'de saklanır ve her çağrıda Optimizer'a aktarılır; yakıt fiyatı
değiştiğinde kod değişmeden sonuçlar değişir.

## 10. Algoritma Karşılaştırması

**İki ayrı karar seviyesi** vardır; algoritmalar karıştırılmamalıdır.

### Seviye 1 — Yol bulma ("A'dan B'ye hangi yoldan?")

| Algoritma | Beklenen davranış |
|---|---|
| **Dijkstra** | Ağırlıklı grafta en kısa yolu garanti eder — referans optimum |
| **A\*** | Aynı sonuç, haversine sezgiseliyle daha az düğüm |
| **BFS** | **Kenar ağırlıklarını yok sayar** → en az kavşaklı yol, en kısa yol değil |
| **DFS** | Optimallik garantisi yok |

BFS'in yanlışlığı rapor için **değerli bir bulgudur**: yol ağı ağırlıklı bir graftır,
ağırlıkları yok sayan arama doğru cevabı veremez.

Metrikler: yol uzunluğu (m), hesaplama süresi (ms), gezilen düğüm sayısı, Dijkstra
optimumundan sapma (%).

### Seviye 2 — Tur sırası ("hangi teslimat hangi sırayla, hangi araçla?")

| Algoritma | Rolü |
|---|---|
| **Naive** (giriş sırası) | Taban çizgi — insan dispatcher davranışı |
| **Nearest Neighbor** | Açgözlü kurucu heuristik |
| **Clarke-Wright savings** | Klasik kurucu heuristik, RouteWise'ın 1. adımı |
| **RouteWise** | Projenin kendi algoritması |
| **DFS-exhaustive** (n ≤ 10) | Tam arama → **gerçek optimum** |

DFS burada meşrudur: backtracking ile tüm permütasyonları gezer. "RouteWise, gerçek
optimumun %X içinde" diyebilmeyi sağlar — raporun en güçlü nicel iddiası.

Metrikler: toplam mesafe, süre, yakıt (L), maliyet (TL), araç sayısı, hesaplama süresi (ms),
optimumdan sapma (%).

### Yöntem notu

Hız karşılaştırması ancak implementasyonlar benzer özenle, aynı dilde, aynı veri yapıları
üzerinde yazıldığında adildir; her algoritma N kez koşturulur, medyan raporlanır. Asıl
ilginç sonuç tek başına hız değil **kalite–süre dengesidir**.

## 11. Analiz ve Raporlama

Projenin **ana amacı** burada ölçülür: dağıtımda ne kadar tasarruf sağlandığı.

### Veri akışı — durumsuzluk korunur

```
Spring Boot          SQL ile koşu geçmişini agrege eder (haftalık / aylık / yıllık)
      │ veri seti (JSON)
      ▼
Python analytics     pandas ile istatistik · scipy ile anlamlılık · matplotlib ile figür
      │ sayılar + figürler
      ▼
Spring Boot → React  ekranda gösterir, dışa aktarır
```

Python veriyi **istekle alır**, sonucu döner. DB sürücüsü gerekmez; §6'daki durumsuzluk
kuralı korunur.

### Python kütüphaneleri

| Kütüphane | İş |
|---|---|
| `numpy` | Mesafe matrisi işlemleri, sayısal hesap |
| `pandas` | Agregasyon, zaman serisi resample, tablo üretimi |
| `scipy` | **İstatistiksel anlamlılık testleri** |
| `matplotlib` | Yayın kalitesinde statik figür — tez raporuna doğrudan gömülür (SVG/PDF) |
| `plotly` | İnteraktif grafik (HTML) |
| `openpyxl` | Excel dışa aktarma |

### İstatistiksel anlamlılık

"RouteWise greedy'den %13 daha iyi" iddiası tek başına zayıftır: *"Bu fark şans eseri
olamaz mı?"* sorusuna cevap vermez.

Aynı senaryo kümesi tüm algoritmalarla koşturulur ve **eşleştirilmiş Wilcoxon işaretli
sıra testi** (dağılım varsayımı gerektirmez) veya paired t-test uygulanır. Çıktı:

> RouteWise, greedy'ye göre anlamlı derecede daha iyi (p < 0.001, n = 50 senaryo).

Maliyeti birkaç satır kod, kazancı iddianın savunulabilirliğidir. Baştan analiz
modülüne konur.

### Grafik üretimi — iki yerde, iki amaçla

| Nerede | Teknoloji | Amaç |
|---|---|---|
| Ekran | React + Recharts | İnteraktif: hover, filtre, canlı güncelleme |
| Rapor | Python + matplotlib | Yayın kalitesi, vektörel (SVG/PDF) — baskıda bozulmaz |

İkisi aynı veriyi farklı tüketiciler için sunar; ekran görüntüsü alıp teze koymak
gerekmez.

### Dışa aktarma

| Format | İçerik |
|---|---|
| **PDF** | Figürlerle birlikte sunuma/teze hazır tasarruf raporu |
| **Excel** | Ham sayılar — danışmanın kendi analizini yapabileceği tablo |

### Raporlanan metrikler

Toplam mesafe (km) · yakıt tüketimi (L) · yakıt maliyeti (TL) · sürücü maliyeti (TL) ·
toplam maliyet (TL) · kullanılan araç sayısı · ortalama teslimat süresi · naive
baseline'a göre tasarruf (%) — haftalık, aylık ve yıllık kırılımda.

## 12. Akışlar

### Optimizasyon akışı

1. Kargo listesi değişir (paket eklenir/çıkarılır, ertelenir, adres değişir)
2. Backend: **doğrulanmış** teslimatları, araçları ve depoyu yükler
3. Backend: matrisi cache'ten okur; yoksa **Python servisinden ister** ve cache'ler
4. Backend: matrisler + kısıtlar + maliyet parametrelerini Optimizer'a POST eder
5. Optimizer: RouteWise ile optimize rotayı **ve** naive baseline'ı hesaplar
6. Backend: rotayı, metrikleri ve karşılaştırmayı kalıcılaştırır
7. Backend: durak bazında ETA hesaplar, alıcılara bildirim gönderir
8. Frontend: haritada rotayı çizer, tasarruf farkını gösterir

### ETA hesabı

```
ETA(durak i) = depodan çıkış saati + Σ ( seyahat süresi + servis süresi ) , 1..i
```

Alıcıya **uygulama içi bildirim + e-posta** ile iletilir. SMS kapsam dışı.

### Karşılaştırma koşusu

Normal optimizasyondan **ayrı** tetiklenir — her değişiklikte tüm algoritmaları koşturmak
gereksiz pahalıdır. Kullanıcı bir kargo listesi seçer, koşuyu başlatır; sonuçlar koşu
kaydı olarak saklanır.

## 13. Dağıtım (Docker)

**Tüm servisler Docker'da** — geliştirme de teslim de aynı Compose üzerinden çalışır.
Danışman/jüri tek komutla sistemi ayağa kaldırabilmelidir.

### Servisler

| Servis | İmaj / kaynak | Port |
|---|---|---|
| `db` | `pgrouting/pgrouting:17-3.5-4.0.1` (PostgreSQL 17.5 + PostGIS 3.5.2 + pgRouting 4.0.1) | 5432 |
| `backend` | `./backend` — Java 21, Spring Boot | 8080 |
| `python` | `./python` — Python 3.12, FastAPI (optimizer + analytics) | 8000 |
| `frontend` | `./frontend` — Node 22, Vite (dev) / nginx (prod) | 5173 / 80 |
| `dataprep` | `./infra/dataprep` — Debian + `osmium-tool` + `osm2pgrouting`, tek seferlik | — |

`postgis/postgis` **kullanılmıyor**: pgRouting içermiyor. Alternatif olarak
`nickblah/pgrouting` yama sürümüne kadar sabitlenebilir etiketler sunar.

### Volume'ler

| Volume | İçerik | Neden pahalı |
|---|---|---|
| `pgdata` | Veritabanı | Yol grafı import'u dakikalar sürer |
| `osmdata` | `turkey-latest.osm.pbf` + kırpılmış Bandırma | 619 MB indirme |
| `graphdata` | Optimizer'ın okuduğu graf dosyası (salt okunur bağlanır) | Export adımı tekrar gerekir |
| `gradle-cache` | Gradle bağımlılıkları | Her build'de yeniden indirme olmasın |

Bu volume'ler kaybedilirse veri hazırlığı baştan yapılır. **`docker compose down -v`
çalıştırılmamalıdır** — `down` (volume'süz) yeterlidir.

### Başlatma sırası

| Servis | Bağımlılık | Healthcheck |
|---|---|---|
| `db` | — | `pg_isready` |
| `backend` | `db` healthy | Actuator `/health` |
| `python` | — | `/health` — graf dosyası yoksa **fail fast**, sessiz başlamaz |
| `frontend` | `backend` (gevşek) | HTTP 200 |

### Veri hazırlığı — tek seferlik, dört adım

```
docker compose run --rm dataprep download   # turkey-latest.osm.pbf  (619 MB)
docker compose run --rm dataprep clip       # osmium extract --polygon bandirma.poly
docker compose run --rm dataprep load       # osm2pgrouting → db
docker compose run --rm dataprep export     # graf dosyası → graphdata
```

Adımlar ayrıdır: biri başarısız olursa baştan başlanmaz. Normal `docker compose up`
akışının parçası **değildir** — günlük başlatma saniyeler sürmelidir.

`osm2pgrouting` topolojiyi kendisi kurar; ayrıca `pgr_createTopology` çalıştırmak
gerekmez. Aracın bilinen kısıtı veriyi tamamen belleğe yüklemesidir — Bandırma
ölçeğinde önemsizdir.

### Windows'a özgü ayarlar

Tüm servisler container'da koştuğu için bunlar baştan yapılandırılır:

| Sorun | Ayar |
|---|---|
| Vite hot reload tetiklenmez (bind-mount dosya olayları iletilmez) | `server.watch.usePolling: true`, `server.host: '0.0.0.0'` |
| Spring Boot her değişiklikte imaj derlerse iterasyon ölür | `bootRun` + devtools, kaynak bind-mount, `.gradle` named volume'de |
| Optimizer her restart'ta grafı yeniden yükler | Graf **kompakt ikili formatta** saklanır, JSON değil |

### Yapılandırma

Sırlar ve ortam ayarları `.env` dosyasında; `.env.example` repoda versiyonlanır,
`.env` **`.gitignore`'da**.

## 14. Test Stratejisi

| Katman | Yaklaşım |
|---|---|
| Optimizer — olurluluk | kapasite ve maksimum süre ihlalinin **asla** üretilmediği testi, her yerel arama hamlesi için |
| Optimizer — Dijkstra doğruluğu | `pgr_dijkstra` sonucuyla birebir karşılaştırma |
| Optimizer — kalite | küçük örneklerde DFS-exhaustive optimumuna karşı gap % regresyon testi |
| Optimizer — determinizm | tohumlanmış SA tekrarlanabilir olmalı |
| Backend — yetkilendirme | her endpoint için rol bazlı erişim testi (alıcı başkasının teslimatını göremez) |
| Backend — durum makinesi | adres değişince doğrulama durumunun geri düştüğü testi |
| Backend — repository | Testcontainers ile gerçek PostgreSQL+PostGIS (H2 **değil**) |
| Backend — REST | slice/controller testleri |
| Frontend | component testleri; form'lar için valid submit + validation + async hata |

En kritik iki test sınıfı: **olurluluk** (sert kısıt ihlali sessizce geçerse algoritma
geçersiz rota döndürür ve kimse fark etmez) ve **yetkilendirme** (rol sızıntısı).

## 15. Açık Kararlar

- [ ] Bandırma OSM idari sınır ilişki kimliği (kurulumda OSM'den teyit edilecek)
- [ ] Ters geocoding kaynağı: Nominatim (self-hosted mu, public API mi?)
- [ ] Yola snap eşiği (metre) ve aykırı değer uyarı eşiği
- [ ] Adres değişikliği günlük limiti ve maliyet artışı eşiği
- [ ] Servis süresi: sabit mi, paket tipine göre mi?
- [ ] Araç filosu seed içeriği (kaç araç, hangi kapasite/tüketim değerleri)
