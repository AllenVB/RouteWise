#!/usr/bin/env python3
"""
bandirma.poly dosyasını Liquibase changeset'ine çevirir.

Neden changeset, neden dataprep adımı değil: sınır poligonu geofence
kontrolünün temelidir. Veri hattı çalıştırılmamış bir kurulumda tablo boş
kalırsa adres doğrulaması sessizce her noktayı kabul ederdi. Şemanın parçası
olarak taşınması, her veritabanında var olmasını garanti eder.

  python3 poly_to_changeset.py bandirma.poly cikti.sql

Çıktı doğrudan dosyaya yazılır, stdout'a değil: Windows'ta stdout kod sayfası
cp1254'tür ve yönlendirme Türkçe karakterleri bozar.
"""

from __future__ import annotations

import sys
from pathlib import Path


def halkalar(path: Path) -> list[list[tuple[float, float]]]:
    """`.poly` biçimini halka listesine çevirir."""
    out: list[list[tuple[float, float]]] = []
    aktif: list[tuple[float, float]] | None = None

    for satir in path.read_text(encoding="utf-8").splitlines():
        s = satir.strip()
        if not s or s == "polygon":
            continue
        if s == "END":
            if aktif is not None:
                out.append(aktif)
                aktif = None
            continue
        parcalar = s.split()
        if len(parcalar) == 2:
            try:
                lon, lat = float(parcalar[0]), float(parcalar[1])
            except ValueError:
                aktif = []          # halka adı satırı
                continue
            if aktif is None:
                aktif = []
            aktif.append((lon, lat))
        else:
            aktif = []              # halka başlığı
    if aktif:
        out.append(aktif)
    return out


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "bandirma.poly")
    rings = [r for r in halkalar(path) if len(r) >= 4]
    if not rings:
        print("poligon okunamadi", file=sys.stderr)
        return 1

    dis = rings[0]
    # WKT kapalı halka ister: ilk nokta sonda tekrarlanmalı.
    if dis[0] != dis[-1]:
        dis = dis + [dis[0]]

    koordinatlar = ",".join(f"{lon} {lat}" for lon, lat in dis)
    wkt = f"POLYGON(({koordinatlar}))"

    lons = [p[0] for p in dis]
    lats = [p[1] for p in dis]

    icerik = f"""--liquibase formatted sql

-- Hizmet alanı sınırı — adres doğrulamasındaki geofence kontrolü bunu kullanır.
-- Kaynak: OpenStreetMap relation/1820912 (Bandırma, Balıkesir)
-- {len(dis)} nokta · boylam {min(lons):.4f}–{max(lons):.4f} · enlem {min(lats):.4f}–{max(lats):.4f}
-- Bu dosya infra/dataprep/poly_to_changeset.py ile üretilmiştir, elle düzenlenmez.

--changeset routewise:020-service-areas
--comment Hizmet alanı tablosu
CREATE TABLE service_areas (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    boundary   GEOMETRY(POLYGON, 4326) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_areas_name UNIQUE (name)
);
CREATE INDEX ix_service_areas_boundary ON service_areas USING GIST (boundary);
--rollback DROP TABLE service_areas;

--changeset routewise:020-bandirma-boundary
--comment Bandırma ilçe sınırı
INSERT INTO service_areas (name, boundary)
VALUES ('Bandırma', ST_GeomFromText('{wkt}', 4326));
--rollback DELETE FROM service_areas WHERE name = 'Bandırma';
"""
    hedef = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("020-service-area.sql")
    hedef.write_text(icerik, encoding="utf-8", newline="\n")
    sys.stderr.write(f"yazildi: {hedef} ({len(dis)} nokta)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
