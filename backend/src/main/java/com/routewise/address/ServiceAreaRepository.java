package com.routewise.address;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

/**
 * Geographic queries for address validation.
 *
 * <p>Written as native PostGIS queries rather than JPA criteria: the whole point
 * is to use {@code ST_Contains} and {@code <->} (the GIST distance operator),
 * which have no JPA equivalent and would otherwise mean pulling thousands of
 * road nodes into memory.
 */
@Repository
public class ServiceAreaRepository {

    private final EntityManager em;

    public ServiceAreaRepository(EntityManager em) {
        this.em = em;
    }

    /** Katman 1 — nokta hizmet alanı sınırları içinde mi? */
    public boolean containsPoint(double lon, double lat) {
        Object result = em.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM service_areas
                    WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
                )
                """)
                .setParameter("lon", lon)
                .setParameter("lat", lat)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    /**
     * Katman 2 — en yakın yol düğümü ve ona olan mesafe.
     *
     * <p>{@code <->} operatörü GIST indeksini kullanır, bu yüzden 9.797 düğüm
     * içinde tarama yapmaz. Mesafe coğrafya (metre) olarak ölçülür.
     *
     * @return {@code null} eğer graf boşsa
     */
    public SnapResult nearestRoadNode(double lon, double lat) {
        var rows = em.createNativeQuery("""
                SELECT v.id,
                       ST_Distance(v.the_geom::geography,
                                   ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS d
                FROM ways_vertices_pgr v
                ORDER BY v.the_geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)
                LIMIT 1
                """)
                .setParameter("lon", lon)
                .setParameter("lat", lat)
                .getResultList();

        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = (Object[]) rows.get(0);
        return new SnapResult(((Number) row[0]).longValue(), ((Number) row[1]).doubleValue());
    }

    /**
     * Katman 3 — noktaya en yakın isimli yolun adı.
     *
     * <p>Harici bir ters geocoding servisi yerine kendi yol grafımızı kullanır:
     * sokak adı zaten OSM verisinde var. Bina numarası vermez, sokak düzeyinde
     * doğrulama sağlar — kullanıcının yanlış mahalleye tıkladığını görmesi için
     * yeterlidir.
     */
    public String nearestStreetName(double lon, double lat) {
        var rows = em.createNativeQuery("""
                SELECT w.name
                FROM ways w
                WHERE w.name IS NOT NULL AND w.name <> ''
                ORDER BY w.the_geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)
                LIMIT 1
                """)
                .setParameter("lon", lon)
                .setParameter("lat", lat)
                .getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    /**
     * Katman 4 — en yakın diğer doğrulanmış adrese uzaklık (metre).
     *
     * @return {@code null} eğer karşılaştırılacak başka adres yoksa
     */
    public Double distanceToNearestVerifiedAddress(double lon, double lat, Long excludeId) {
        // Null parametre gönderilmez: PostgreSQL tipini çıkaramaz ve sorgu
        // "could not determine data type of parameter" ile patlar. Kimlikler
        // pozitif olduğu için -1 hiçbir satırla eşleşmez ve filtreyi etkisizler.
        long exclude = excludeId == null ? -1L : excludeId;

        var rows = em.createNativeQuery("""
                SELECT ST_Distance(a.location::geography,
                                   ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
                FROM addresses a
                WHERE a.verification_status = 'DOGRULANDI'
                  AND a.id <> :excludeId
                ORDER BY a.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)
                LIMIT 1
                """)
                .setParameter("lon", lon)
                .setParameter("lat", lat)
                .setParameter("excludeId", exclude)
                .getResultList();
        return rows.isEmpty() ? null : ((Number) rows.get(0)).doubleValue();
    }

    public record SnapResult(long nodeId, double distanceM) {}
}
