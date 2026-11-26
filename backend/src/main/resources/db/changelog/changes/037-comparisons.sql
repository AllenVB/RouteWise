--liquibase formatted sql

-- Faz 6 — algoritma karşılaştırması. Her karşılaştırma bir satır: girdisi ve
-- Python servisinin döndüğü tam sonuç (yollar ve koordinatlar dahil) saklanır,
-- F8 ekranı onu yeniden çizebilir. Algoritma başına metrikler ayrıca
-- `path_comparison_runs`'ta durur: analiz ve anlamlılık testi oradan okur.

--changeset routewise:037-comparisons
--comment Karşılaştırma koşuları — Seviye 1 (yol) ve Seviye 2 (tur)
CREATE TABLE comparisons (
    id          BIGSERIAL   PRIMARY KEY,
    level       VARCHAR(10) NOT NULL,
    plan_id     BIGINT,
    params      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    result      JSONB       NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_comparison_plan FOREIGN KEY (plan_id)    REFERENCES delivery_plans (id) ON DELETE CASCADE,
    CONSTRAINT fk_comparison_user FOREIGN KEY (created_by) REFERENCES users (id)          ON DELETE SET NULL,
    CONSTRAINT ck_comparison_level CHECK (level IN ('PATH', 'TOUR'))
);
CREATE INDEX ix_comparison_created ON comparisons (created_at);
--rollback DROP TABLE comparisons;

--changeset routewise:037-path-runs-comparison
--comment Seviye 1 satırları karşılaştırmasına bağlanır; DFS sapması %9999'u aşabilir
ALTER TABLE path_comparison_runs ADD COLUMN comparison_id BIGINT;
ALTER TABLE path_comparison_runs ADD COLUMN weight VARCHAR(10) NOT NULL DEFAULT 'distance';
ALTER TABLE path_comparison_runs ADD COLUMN path_duration_s NUMERIC(10,1);
ALTER TABLE path_comparison_runs ADD COLUMN edge_count INTEGER;
ALTER TABLE path_comparison_runs ADD COLUMN found BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE path_comparison_runs ALTER COLUMN deviation_pct TYPE NUMERIC(10,2);
ALTER TABLE path_comparison_runs ADD CONSTRAINT fk_path_run_comparison
    FOREIGN KEY (comparison_id) REFERENCES comparisons (id) ON DELETE CASCADE;
ALTER TABLE path_comparison_runs ADD CONSTRAINT ck_path_weight CHECK (weight IN ('distance', 'duration'));
CREATE INDEX ix_path_comparison ON path_comparison_runs (comparison_id);
--rollback DROP INDEX ix_path_comparison; ALTER TABLE path_comparison_runs DROP CONSTRAINT ck_path_weight; ALTER TABLE path_comparison_runs DROP CONSTRAINT fk_path_run_comparison; ALTER TABLE path_comparison_runs ALTER COLUMN deviation_pct TYPE NUMERIC(6,2); ALTER TABLE path_comparison_runs DROP COLUMN found; ALTER TABLE path_comparison_runs DROP COLUMN edge_count; ALTER TABLE path_comparison_runs DROP COLUMN path_duration_s; ALTER TABLE path_comparison_runs DROP COLUMN weight; ALTER TABLE path_comparison_runs DROP COLUMN comparison_id;
