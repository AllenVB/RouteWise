#!/usr/bin/env bash
# Bandırma yol grafı veri hattı.
# Adımlar ayrıdır: biri başarısız olursa baştan başlanmaz.
set -euo pipefail

OSM_DIR=/data/osm
GRAPH_DIR=/data/graph
TR_PBF="$OSM_DIR/turkey-latest.osm.pbf"
BND_PBF="$OSM_DIR/bandirma.osm.pbf"
# osm2pgrouting 2.3.8 PBF okuyamaz, yalnızca XML kabul eder.
# Bandırma küçük olduğu için XML'e çevirmek ucuz.
BND_XML="$OSM_DIR/bandirma.osm"
POLY=/opt/dataprep/bandirma.poly

# ÖNEMLİ: araç profili kullanılır, jenerik profil DEĞİL.
# mapconfig.xml yaya ve bisiklet yollarını da grafa katar; kargo aracı bu
# yollardan rota alır ve mesafeler gerçek dışı çıkar. Araç profili yalnızca
# motorlu taşıtın geçebileceği yol tiplerini alır.
MAPCONFIG="${OSM2PGR_CONFIG:-/usr/share/osm2pgrouting/mapconfig_for_cars.xml}"

log() { printf '\n\033[1;34m[dataprep]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[dataprep HATA]\033[0m %s\n' "$*" >&2; exit 1; }

step_download() {
    mkdir -p "$OSM_DIR"
    if [ -s "$TR_PBF" ]; then
        log "Türkiye çıkartması zaten var ($(du -h "$TR_PBF" | cut -f1)), indirme atlandı."
        log "Yeniden indirmek için: rm $TR_PBF"
        return 0
    fi
    [ -n "${OSM_SOURCE_URL:-}" ] || die "OSM_SOURCE_URL tanımlı değil (.env)"
    log "İndiriliyor: $OSM_SOURCE_URL  (~619 MB, sürebilir)"
    # -C - : yarım kalan indirmeyi kaldığı yerden sürdürür
    curl -L -C - --fail --retry 3 --retry-delay 5 \
         -A "RouteWise-thesis/0.1" \
         -o "$TR_PBF" "$OSM_SOURCE_URL" \
        || die "indirme başarısız"
    log "Bitti: $(du -h "$TR_PBF" | cut -f1)"
}

step_clip() {
    [ -s "$TR_PBF" ] || die "Türkiye dosyası yok — önce 'download' çalıştır"
    [ -s "$POLY" ]   || die "Sınır poligonu bulunamadı: $POLY"
    log "Bandırma ilçe sınırıyla kırpılıyor (bbox değil, poligon)"
    osmium extract --polygon "$POLY" "$TR_PBF" -o "$BND_PBF" --overwrite \
        || die "osmium extract başarısız"
    log "Bitti: $(du -h "$BND_PBF" | cut -f1)"
    log "osm2pgrouting için XML'e çevriliyor"
    osmium cat "$BND_PBF" -o "$BND_XML" --overwrite || die "pbf -> xml çevrimi başarısız"
    log "XML: $(du -h "$BND_XML" | cut -f1)"
}

step_load() {
    [ -s "$BND_XML" ] || die "Bandırma XML dosyası yok — önce 'clip' çalıştır"
    [ -f "$MAPCONFIG" ] || die "mapconfig bulunamadı: $MAPCONFIG"
    log "osm2pgrouting ile yükleniyor -> $PGDATABASE@$PGHOST"
    log "profil: $(basename "$MAPCONFIG")"
    # --clean: ways / ways_vertices_pgr tablolarını sıfırlar.
    # Yalnızca graf tablolarını etkiler, uygulama tablolarına dokunmaz.
    osm2pgrouting \
        --f "$BND_XML" \
        --conf "$MAPCONFIG" \
        --dbname "$PGDATABASE" \
        --host "$PGHOST" \
        --port "$PGPORT" \
        --username "$PGUSER" \
        --password "$PGPASSWORD" \
        --clean \
        || die "osm2pgrouting başarısız"

    log "Doğrulama"
    psql -tA -c "SELECT 'ways: '||count(*) FROM ways;"
    psql -tA -c "SELECT 'düğüm: '||count(*) FROM ways_vertices_pgr;"
    local n
    n=$(psql -tA -c "SELECT count(*) FROM ways;")
    [ "$n" -gt 100 ] || die "ways tablosu şüpheli derecede küçük ($n) — poligonu kontrol et"
}

step_export() {
    mkdir -p "$GRAPH_DIR"
    log "Graf ikili dosyaya aktarılıyor"
    python3 /opt/dataprep/export_graph.py "$GRAPH_DIR/bandirma-graph.npz" || die "export başarısız"
    log "Bitti: $(du -h "$GRAPH_DIR/bandirma-graph.npz" | cut -f1)"
}

step_verify() {
    log "pgRouting referans sorgusu (pgr_dijkstra)"
    psql -tA <<'SQL'
-- length_m'yi maliyet olarak veriyoruz ki sonuç METRE cinsinden çıksın.
-- (ways.cost derece cinsindendir, insan için okunaksızdır.)
-- Negatif reverse_cost tek yönlü yol demektir; işareti koruyoruz.
WITH uc AS (
  SELECT (SELECT id FROM ways_vertices_pgr ORDER BY id ASC  LIMIT 1) AS s,
         (SELECT id FROM ways_vertices_pgr ORDER BY id DESC LIMIT 1) AS t
)
SELECT 'yol uzunlugu: '||COALESCE(round(sum(cost)::numeric)::text||' m', 'yol bulunamadi')
     ||'   durak sayisi: '||count(*)
FROM pgr_dijkstra(
  'SELECT gid AS id, source, target,
          length_m AS cost,
          CASE WHEN reverse_cost < 0 THEN -1 ELSE length_m END AS reverse_cost
   FROM ways',
  (SELECT s FROM uc), (SELECT t FROM uc), directed := true);
SQL
}

usage() {
    cat <<'EOF'
Kullanım: docker compose --profile tools run --rm dataprep <adım>

  download   Geofabrik Türkiye çıkartmasını indirir (~619 MB, bir kez)
  clip       Bandırma ilçe poligonuyla kırpar
  load       osm2pgrouting ile veritabanına yükler
  export     Grafı Python servisinin okuyacağı ikili dosyaya aktarır
  verify     pgr_dijkstra ile örnek sorgu çalıştırır
  all        Yukarıdakilerin hepsi, sırayla
EOF
}

case "${1:-help}" in
    download) step_download ;;
    clip)     step_clip ;;
    load)     step_load ;;
    export)   step_export ;;
    verify)   step_verify ;;
    all)      step_download; step_clip; step_load; step_export; step_verify ;;
    *)        usage ;;
esac
