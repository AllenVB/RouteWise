--liquibase formatted sql

-- Faz 5 — kuryenin "Rota değişti" ekranı (K5). Her yeniden rotalama, kuryenin
-- gördüğü listede neyin değiştiğini de kaydeder: çıkan, adresi taşınan ve sırası
-- değişen duraklar. Günlük limit dolduğunda sıra korunur ama değişiklik yine
-- yazılır (order_kept); limit sayımı bu satırları saymaz.

--changeset routewise:035-reroute-changes
--comment Yeniden rotalamada kuryeye gösterilen değişiklikler
ALTER TABLE reroute_events ADD COLUMN order_kept BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE reroute_events ADD COLUMN changes JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX ix_reroute_route_created ON reroute_events (route_id, created_at);
--rollback DROP INDEX ix_reroute_route_created; ALTER TABLE reroute_events DROP COLUMN changes; ALTER TABLE reroute_events DROP COLUMN order_kept;
