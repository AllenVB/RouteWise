# Bandırma yol grafı veri hattı — tek seferlik araç imajı.
# osm2pgrouting 3.x Debian trixie'de paketlidir.
FROM debian:trixie-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        osmium-tool \
        osm2pgrouting \
        postgresql-client \
        python3 \
        python3-numpy \
        python3-psycopg2 \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/dataprep
COPY bandirma.poly export_graph.py run.sh ./

# Windows'ta düzenlenen betikler CRLF taşıyabilir; bash bunu "\r: command not
# found" diye reddeder. Kopyaladıktan sonra temizliyoruz.
RUN sed -i 's/\r$//' run.sh export_graph.py && chmod +x run.sh

VOLUME ["/data/osm", "/data/graph"]
ENTRYPOINT ["/opt/dataprep/run.sh"]
CMD ["help"]
