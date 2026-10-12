# RouteWise

**Smart Dynamic Route & Fleet Optimization**

Lojistik Dağıtım Süreçlerinde Dinamik Rota ve Araç Optimizasyonu Sağlayan Akıllı Yazılım Sistemi

> **Durum:** Faz 0 — altyapı ve veri hattı kuruluyor. Uygulama kodu henüz yazılmadı.
> Yol planı: [docs/roadmap.md](docs/roadmap.md)

---

## Amaç

Kargo dağıtım süreçlerinde oluşan **yakıt, zaman ve araç kullanım maliyetlerini**
azaltmak amacıyla dinamik rota optimizasyonu gerçekleştiren bir yazılım sistemi
geliştirmek.

**Kapsam:** Bandırma içi kargo dağıtımı, tek dağıtım merkezi.

## Özgün Yanı

Sistem yalnızca en kısa rotayı bulmaz. Maliyet, rota hesaplandıktan *sonra* eklenen bir
rapor alanı değil, **amaç fonksiyonunun parçasıdır** — algoritmanın her kararını bu
değerler yönlendirir:

| Değişken | Rol |
|---|---|
| Araç kapasitesi | sert kısıt |
| Çalışma günü süresi | sert kısıt |
| Teslimat önceliği | ceza katsayısı |
| Mesafe · süre · yakıt · sürücü maliyeti | amaç fonksiyonu bileşenleri |

### Çözülen problem

**CVRP + maksimum rota süresi** — tek depo, kapasiteli araç filosu, çalışma günü
sınırı; üstüne dağıtım sırasında **dinamik yeniden rotalama**.

Müşteri bazlı zaman penceresi (VRPTW) kapsam dışıdır: "tüm teslimatlar gün içinde
bitsin" rota başına tek bir süre üst sınırıdır.

### Algoritma

Hazır çözücü (OR-Tools vb.) kullanılmaz; algoritma projeye özgü geliştirilir:

```
Clarke-Wright savings  →  2-opt · relocate · exchange · Or-opt  →  simulated annealing
   (başlangıç çözümü)            (yerel arama)                (tohumlanmış, sıcak başlangıçlı)
```

Her hamlede kapasite ve maksimum rota süresi olurluluğu kontrol edilir.

Çözüm kalitesi iki referansa karşı ölçülür: her koşuda hesaplanan **naive baseline**'a
göre tasarruf yüzdesi, ve küçük örneklerde **DFS-exhaustive** ile bulunan gerçek
optimuma göre sapma.

## Mimari

| Bileşen | Teknoloji | Sorumluluk |
|---|---|---|
| **Backend** | Java 21 · Spring Boot 3.4+ | Veri sahipliği, domain, JWT, matris cache, ETA, raporlar |
| **Python servisi** | Python 3.12 · FastAPI | Optimizasyon algoritmaları + analiz (durumsuz, DB erişimi yok) |
| **Frontend** | React 19 · Vite · TypeScript · MapLibre | Üç rol için arayüz, harita, grafikler |
| **Veritabanı** | PostgreSQL 17.5 · PostGIS 3.5.2 · pgRouting 4.0.1 | İş verisi, coğrafi sorgular, yol grafı |

**Mesafe verisi için harici API kullanılmaz.** Bandırma OSM çıkartması kendi
veritabanımıza yüklenir; mesafe ve süre matrisi kendi Dijkstra implementasyonumuzla
bu graf üzerinden hesaplanır. Kota, API anahtarı ve internet bağımlılığı yoktur.

Ayrıntı: [docs/architecture.md](docs/architecture.md)

## Kurulum

### Gereksinimler
- Docker Desktop (çalışır durumda)
- Disk: ~10 GB (OSM verisi + veritabanı)

### Adımlar

```bash
cd infra
cp .env.example .env          # POSTGRES_PASSWORD değerini değiştir
docker compose up -d db
```

Veritabanı hazır olunca yol grafını kur (tek seferlik, ~619 MB indirme):

```bash
docker compose --profile tools run --rm dataprep all
```

Adımlar tek tek de çalıştırılabilir: `download` · `clip` · `load` · `export` · `verify`.
Ayrıntı: [infra/dataprep/README.md](infra/dataprep/README.md)

> **`docker compose down -v` çalıştırmayın.** Volume'ler 619 MB indirme ve dakikalarca
> süren import içerir; `-v` hepsini siler. Volume'süz `down` yeterlidir.

### Tüm servisleri çalıştır

Yol grafı kurulduktan sonra:

```bash
docker compose up -d
```

| Servis | Adres | İçerik |
|---|---|---|
| Frontend | http://localhost:5173 | React + Vite |
| Backend | http://localhost:8090 | Spring Boot — `/actuator/health`, `/system/status` |
| Python | http://localhost:8000 | FastAPI — `/health` |
| Veritabanı | localhost:5432 | PostgreSQL + PostGIS + pgRouting |

> Backend 8080 yerine **8090**'da yayınlanır; container içi port 8080'dir.
> Sebebi bu makinede 8080–8086 aralığının başka bir projeye ait olmasıdır.
> `infra/.env` içindeki `BACKEND_PORT` ile değiştirilebilir.

### Doğrulama

```bash
curl http://localhost:8090/system/status
```

Yanıt hem backend'i hem Python servisini ve yüklenen yol grafını göstermelidir:
9.797 düğüm, 1.259,2 km.

Testler:

```bash
docker compose run --rm --no-deps python pytest -q
```

## Proje yapısı

```
backend/      Spring Boot          (Faz 1a)
python/       FastAPI              (Faz 1a)
frontend/     React + Vite         (Faz 1a)
infra/        Docker Compose + veri hattı
docs/         mimari, yol planı, analiz dokümanları
```

## Dokümantasyon

| Doküman | İçerik |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Sistem mimarisi — 15 bölüm |
| [docs/roadmap.md](docs/roadmap.md) | Geliştirme yol planı — 8 faz, 14–18 hafta |
| [docs/RouteWise-Detayli-Sistem-Analizi.pdf](docs/RouteWise-Detayli-Sistem-Analizi.pdf) | Görsel sistem analizi — 25 sayfa, ER diyagramı dahil |
| [docs/proposal.md](docs/proposal.md) | Orijinal proje önerisi |
| [infra/dataprep/README.md](infra/dataprep/README.md) | Veri hattı ve sınır poligonunun kaynağı |

## Başarı Kriterleri

1. Teslimat noktalarına göre otomatik ve optimize edilmiş rotaların oluşturulabilmesi
2. Teslimat bilgilerindeki değişikliklerde rotanın yeniden oluşturulabilmesi
3. Araç kapasitesi ve teslimat önceliklerinin rota planlamasına dahil edilebilmesi
4. Toplam mesafe ve tahmini yakıt tüketiminin hesaplanabilmesi
5. Optimize edilmiş rota ile standart rota arasındaki mesafe, süre ve maliyet farkının ölçülebilmesi
6. Gerçekleştirilen dağıtımlara ait verilerin saklanabilmesi
7. Haftalık, aylık ve yıllık tasarruf analizlerinin raporlanabilmesi
8. Sistemin farklı araç ve dağıtım senaryolarında çalıştırılabilmesi
9. Geleneksel rota planlamasına kıyasla ölçülebilir bir verimlilik artışı sağlanması
