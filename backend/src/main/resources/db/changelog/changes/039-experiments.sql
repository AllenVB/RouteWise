--liquibase formatted sql

-- Faz 6 — Analiz. Bir deney, tohumdan üretilen N sentetik senaryoda Seviye 2 tur
-- karşılaştırmasıdır (gerçek Bandırma yol grafı, gerçek maliyet ve çalışma günü).
-- Her senaryo bir `comparisons` satırıdır (experiment_id, scenario_seed); deneyin
-- özeti (sapma istatistikleri, Wilcoxon / eşleştirilmiş t) burada JSON olarak
-- durur. Aynı base_seed ve parametreler aynı deneyi yeniden üretir.

--changeset routewise:039-experiments
--comment Tur karşılaştırma deneyleri — anlamlılık testinin senaryo kümesi
CREATE TABLE experiments (
    id              BIGSERIAL     PRIMARY KEY,
    kind            VARCHAR(10)   NOT NULL DEFAULT 'TOUR',
    status          VARCHAR(10)   NOT NULL DEFAULT 'QUEUED',
    scenario_count  INTEGER       NOT NULL,
    stop_count      INTEGER       NOT NULL,
    radius_m        INTEGER       NOT NULL,
    repeats         INTEGER       NOT NULL,
    base_seed       BIGINT        NOT NULL,
    vehicle_id      BIGINT,
    completed       INTEGER       NOT NULL DEFAULT 0,
    failed          INTEGER       NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    summary         JSONB,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT fk_experiment_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id) ON DELETE SET NULL,
    CONSTRAINT fk_experiment_user    FOREIGN KEY (created_by) REFERENCES users (id)    ON DELETE SET NULL,
    CONSTRAINT ck_experiment_kind    CHECK (kind IN ('TOUR')),
    CONSTRAINT ck_experiment_status  CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_experiment_counts  CHECK (scenario_count > 0 AND completed >= 0 AND failed >= 0
                                            AND completed + failed <= scenario_count)
);
CREATE INDEX ix_experiment_created ON experiments (created_at);
--rollback DROP TABLE experiments;

--changeset routewise:039-comparisons-experiment
--comment Senaryo karşılaştırmaları deneyine ve tohumuna bağlanır
ALTER TABLE comparisons ADD COLUMN experiment_id BIGINT;
ALTER TABLE comparisons ADD COLUMN scenario_seed BIGINT;
ALTER TABLE comparisons ADD CONSTRAINT fk_comparison_experiment
    FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE;
CREATE INDEX ix_comparison_experiment ON comparisons (experiment_id);
--rollback DROP INDEX ix_comparison_experiment; ALTER TABLE comparisons DROP CONSTRAINT fk_comparison_experiment; ALTER TABLE comparisons DROP COLUMN scenario_seed; ALTER TABLE comparisons DROP COLUMN experiment_id;
