--liquibase formatted sql

-- Faz 4 — kurye: durağın sonucu. "Ulaşılamadı" sebebi saklanmazsa firma neden
-- teslim edilemediğini bilemez ve kargo bir sonraki gün aynı sebeple yine döner.

--changeset routewise:033-stop-failure-reason
--comment Ulaşılamayan durağın sebebi
ALTER TABLE route_stops ADD COLUMN failure_reason VARCHAR(300);
ALTER TABLE route_stops ADD CONSTRAINT ck_stops_failure_reason
    CHECK (status <> 'ULASILAMADI' OR failure_reason IS NOT NULL);
--rollback ALTER TABLE route_stops DROP CONSTRAINT ck_stops_failure_reason; ALTER TABLE route_stops DROP COLUMN failure_reason;
