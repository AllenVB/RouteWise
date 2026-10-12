--liquibase formatted sql

-- Uzantılar normalde infra/db/init/ ile kurulur (yeni veritabanı ilk açılışta).
-- Burada da tekrar edilir ki şema, init betikleri çalışmamış bir veritabanında
-- da kendi başına kurulabilsin.

--changeset routewise:000-extensions
--comment PostGIS ve pgRouting uzantıları
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgrouting;
--rollback SELECT 1;
