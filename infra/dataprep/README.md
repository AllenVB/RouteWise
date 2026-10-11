# Veri Hattı — Bandırma Yol Grafı

Tek seferlik kurulum. Normal `docker compose up` akışının parçası **değildir**.

## Çalıştırma

```bash
cd infra
cp .env.example .env          # değerleri doldur
docker compose up -d db       # veritabanı ayağa kalksın
docker compose --profile tools run --rm dataprep all
```

Adımlar ayrı ayrı da çalıştırılabilir — biri başarısız olursa baştan başlanmaz:

| Adım | İş | Süre |
|---|---|---|
| `download` | Geofabrik Türkiye çıkartması (~619 MB) | bağlantıya göre |
| `clip` | Bandırma ilçe poligonuyla kırpma | ~1 dk |
| `load` | `osm2pgrouting` ile veritabanına yükleme | dakikalar |
| `export` | Grafı `.npz` ikili dosyasına aktarma | saniyeler |
| `verify` | `pgr_dijkstra` ile örnek sorgu | saniyeler |

`download` adımı yarım kalan indirmeyi kaldığı yerden sürdürür (`curl -C -`).
Dosya zaten varsa indirmeyi atlar.

## Sınır poligonunun kaynağı

`bandirma.poly` dosyası **repoya işlenmiştir**. Sebebi tekrar üretilebilirliktir:
tez, üçüncü taraf bir servisin ayakta kalmasına bağımlı olmamalıdır.

| | |
|---|---|
| OSM ilişkisi | `relation/1820912` — Bandırma, Balıkesir |
| Tür | `boundary/administrative` |
| Üretildiği servis | `polygons.openstreetmap.fr/get_poly.py?id=1820912&params=0` |
| Üretim tarihi | 11.10.2026 |
| Halka sayısı | 1 |
| Nokta sayısı | 4211 |
| Sınırlar | boylam 27.6218 – 28.2357 · enlem 40.1009 – 40.4002 |

Yeniden üretmek gerekirse:

```bash
curl "https://polygons.openstreetmap.fr/index.py?id=1820912" -o /dev/null
curl "https://polygons.openstreetmap.fr/get_poly.py?id=1820912&params=0" -o bandirma.poly
```

### Neden bbox değil poligon

Bandırma'yı çevreleyen bir dikdörtgen, komşu ilçelerden gereksiz yol ağı taşır:
graf şişer, Dijkstra yavaşlar. İlçe sınır poligonu bunu önler.

Aynı poligon ikinci bir işe daha yarar: adres doğrulamasındaki **geofence**
kontrolü (`ST_Contains`) de bu sınırı kullanır. Haritada Bandırma dışına
tıklandığında adres bu sayede reddedilir.

## Üretilen tablolar

`osm2pgrouting` topolojiyi kendisi kurar; ayrıca `pgr_createTopology`
çalıştırmak gerekmez.

| Tablo | İçerik |
|---|---|
| `ways` | Yol parçaları — `source`, `target`, `cost`, `cost_s`, `length_m` |
| `ways_vertices_pgr` | Kavşak düğümleri — `id`, `the_geom` |

Bu tablolara uygulama **yazmaz**; salt okunur referans veridir.

## Graf dosyası

`export` adımı `graphdata` volume'üne `bandirma-graph.npz` yazar. Python servisi
bunu açılışta belleğe yükler.

`cost` ve `reverse_cost` alanlarında **negatif değer, o yönde geçilemez** demektir
(tek yönlü yollar). `cost_s` / `reverse_cost_s` saniye cinsindendir ve OSM
`maxspeed` etiketinden hesaplanır — süre kısıtı ve ETA bu alanlara dayanır.

## Dikkat

Volume'ler pahalıdır: 619 MB indirme ve dakikalarca süren import.
**`docker compose down -v` çalıştırılmamalıdır** — volume'süz `down` yeterlidir.
