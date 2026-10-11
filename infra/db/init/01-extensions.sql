-- Veritabanı ilk kez oluşturulurken bir kez çalışır (pgdata volume boşsa).
-- İmaj uzantıları kendiliğinden kurmayabilir; açıkça kuruyoruz.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgrouting;

-- Kurulumu doğrula — healthcheck bu satıra güvenir.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
        RAISE EXCEPTION 'postgis uzantısı kurulamadı';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgrouting') THEN
        RAISE EXCEPTION 'pgrouting uzantısı kurulamadı';
    END IF;
    RAISE NOTICE 'postgis ve pgrouting kuruldu.';
END
$$;
