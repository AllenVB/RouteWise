#!/usr/bin/env python3
"""
Bandırma yol grafını PostGIS'ten okuyup Python servisinin açılışta yükleyeceği
kompakt ikili dosyaya (.npz) aktarır.

Neden ikili: Python servisi her yeniden başlatmada grafı belleğe yükler. JSON
ile bu saniyeler sürer ve geliştirme döngüsünü yavaşlatır.

Neden dosya: Python servisi iş verisi açısından durumsuzdur ve veritabanına
erişmez. Yol grafı değişmeyen referans veridir; bu kuralın tek istisnasıdır ve
DB bağlantısı yerine dosyadan okunarak karşılanır.

Çalıştırma:  python3 export_graph.py /data/graph/bandirma-graph.npz
"""

import os
import sys

import numpy as np
import psycopg2

# osm2pgrouting'in ürettiği `ways` tablosu:
#   cost / reverse_cost  -> uzunluk birimi (derece); negatif = o yönde geçilemez
#   cost_s / reverse_cost_s -> saniye (maxspeed'den hesaplanır)
#   length_m -> metre
EDGE_SQL = """
SELECT gid,
       source,
       target,
       length_m,
       cost,
       reverse_cost,
       cost_s,
       reverse_cost_s
FROM ways
WHERE source IS NOT NULL AND target IS NOT NULL
ORDER BY gid
"""

NODE_SQL = """
SELECT id,
       ST_X(the_geom) AS lon,
       ST_Y(the_geom) AS lat
FROM ways_vertices_pgr
ORDER BY id
"""


def main() -> int:
    out_path = sys.argv[1] if len(sys.argv) > 1 else "/data/graph/bandirma-graph.npz"

    conn = psycopg2.connect(
        host=os.environ.get("PGHOST", "db"),
        port=os.environ.get("PGPORT", "5432"),
        dbname=os.environ["PGDATABASE"],
        user=os.environ["PGUSER"],
        password=os.environ["PGPASSWORD"],
    )

    with conn, conn.cursor() as cur:
        cur.execute(EDGE_SQL)
        edges = cur.fetchall()
        cur.execute(NODE_SQL)
        nodes = cur.fetchall()

    conn.close()

    if not edges:
        print("HATA: ways tablosu boş — once 'load' adimini calistir", file=sys.stderr)
        return 1
    if not nodes:
        print("HATA: ways_vertices_pgr bos", file=sys.stderr)
        return 1

    def col(rows, i, dtype):
        # NULL -> NaN/0; cost alanlarinda NULL gecilemez kenar anlamina gelir
        if dtype == np.float64:
            return np.array([np.nan if r[i] is None else float(r[i]) for r in rows],
                            dtype=np.float64)
        return np.array([0 if r[i] is None else int(r[i]) for r in rows], dtype=dtype)

    edge_gid = col(edges, 0, np.int64)
    edge_src = col(edges, 1, np.int64)
    edge_tgt = col(edges, 2, np.int64)
    length_m = col(edges, 3, np.float64)
    cost = col(edges, 4, np.float64)
    reverse_cost = col(edges, 5, np.float64)
    cost_s = col(edges, 6, np.float64)
    reverse_cost_s = col(edges, 7, np.float64)

    node_id = col(nodes, 0, np.int64)
    node_lon = col(nodes, 1, np.float64)
    node_lat = col(nodes, 2, np.float64)

    np.savez_compressed(
        out_path,
        edge_gid=edge_gid,
        edge_source=edge_src,
        edge_target=edge_tgt,
        edge_length_m=length_m,
        edge_cost=cost,
        edge_reverse_cost=reverse_cost,
        edge_cost_s=cost_s,
        edge_reverse_cost_s=reverse_cost_s,
        node_id=node_id,
        node_lon=node_lon,
        node_lat=node_lat,
    )

    # Ozet — rakamlar makul degilse poligon veya import hatalidir.
    one_way = int(np.sum(reverse_cost < 0)) + int(np.sum(cost < 0))
    print(f"kenar      : {len(edge_gid):,}")
    print(f"dugum      : {len(node_id):,}")
    print(f"tek yonlu  : {one_way:,}")
    print(f"toplam km  : {np.nansum(length_m) / 1000:,.1f}")
    print(f"bbox lon   : {node_lon.min():.4f} .. {node_lon.max():.4f}")
    print(f"bbox lat   : {node_lat.min():.4f} .. {node_lat.max():.4f}")
    print(f"yazildi    : {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
