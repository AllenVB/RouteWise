# infra/

Docker Compose tanımları ve tek seferlik veri hattı.

```
infra/
  docker-compose.yml     db + dataprep  (uygulama servisleri Faz 1a'da eklenecek)
  .env.example           kopyala → .env
  db/init/               ilk açılışta çalışan SQL (postgis, pgrouting uzantıları)
  dataprep/              OSM veri hattı — ayrıntı: dataprep/README.md
```

## Hızlı başlangıç

```bash
cd infra
cp .env.example .env
docker compose up -d db
docker compose --profile tools run --rm dataprep all
```

## Servisler

| Servis | İmaj | Port | Not |
|---|---|---|---|
| `db` | `pgrouting/pgrouting:17-3.5-4.0.1` | 5432 | PostgreSQL 17.5 · PostGIS 3.5.2 · pgRouting 4.0.1 |
| `dataprep` | yerel build | — | `profiles: ["tools"]` — normal `up` ile başlamaz |

`postgis/postgis` kullanılmıyor: pgRouting içermiyor.

## Volume'ler

| Volume | İçerik | Yeniden üretme maliyeti |
|---|---|---|
| `routewise-pgdata` | Veritabanı | import dakikalar sürer |
| `routewise-osmdata` | OSM dosyaları | 619 MB indirme |
| `routewise-graphdata` | Graf `.npz` dosyası | export adımı |

> **`docker compose down -v` çalıştırmayın.** Volume'süz `down` yeterlidir.
> `-v` bu üç volume'ü siler ve veri hattının tamamı baştan koşulması gerekir.

## Healthcheck notu

`db` healthcheck'i yalnızca `pg_isready` kullanmaz; `pgrouting` uzantısının
kurulduğunu da sorgular. Sebebi şu: Postgres bağlantı kabul etmeye başladığında
init betikleri henüz bitmemiş olabilir ve servis "hazır" görünürken uzantılar
eksik olur. `dataprep` bu healthcheck'e güvendiği için kontrol sıkı tutuldu.
