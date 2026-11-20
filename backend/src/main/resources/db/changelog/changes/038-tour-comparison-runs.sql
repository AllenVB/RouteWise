--liquibase formatted sql

-- Faz 6 — Seviye 2 (tur sırası) karşılaştırmasının algoritma başına satırları.
-- Anlamlılık testi (Wilcoxon / eşleştirilmiş t) aynı senaryoda algoritmaları
-- eşleştirir: senaryo = comparison_id. Sapma, referansa göredir; referansın kesin
-- optimum olup olmadığı reference_is_optimal'da (n > 10 ise en iyi bulunan).

--changeset routewise:038-tour-comparison-runs
--comment Seviye 2 deney kayıtları — NAIVE/GREEDY_NN/SAVINGS/ROUTEWISE/DFS_EXHAUSTIVE
CREATE TABLE tour_comparison_runs (
    id                   BIGSERIAL     PRIMARY KEY,
    comparison_id        BIGINT        NOT NULL,
    algorithm            VARCHAR(20)   NOT NULL,
    stop_count           INTEGER       NOT NULL,
    feasible             BOOLEAN       NOT NULL,
    objective            NUMERIC(12,2),
    cost_try             NUMERIC(12,2),
    distance_m           NUMERIC(12,2),
    duration_s           NUMERIC(10,1),
    computation_time_ms  NUMERIC(12,3) NOT NULL,
    gap_pct              NUMERIC(10,3),
    reference_is_optimal BOOLEAN       NOT NULL,
    random_seed          BIGINT,
    objective_min        NUMERIC(12,2),
    objective_max        NUMERIC(12,2),
    explored             BIGINT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_tour_run_comparison FOREIGN KEY (comparison_id) REFERENCES comparisons (id) ON DELETE CASCADE,
    CONSTRAINT ck_tour_algorithm CHECK (algorithm IN ('NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE', 'DFS_EXHAUSTIVE')),
    CONSTRAINT ck_tour_stop_count CHECK (stop_count >= 0)
);
CREATE INDEX ix_tour_run_comparison ON tour_comparison_runs (comparison_id);
CREATE INDEX ix_tour_run_algorithm ON tour_comparison_runs (algorithm);
--rollback DROP TABLE tour_comparison_runs;
