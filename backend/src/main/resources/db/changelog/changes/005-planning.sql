--liquibase formatted sql

-- Planlama ve optimizasyon zinciri:
--   delivery_plans 1:N optimization_runs 1:N routes 1:N route_stops
-- optimization_runs projenin ölçüm omurgasıdır; tasarruf yüzdesi buradan türetilir.

--changeset routewise:005-delivery-plans
--comment Günlük dağıtım planı — depo başına günde tek plan
CREATE TABLE delivery_plans (
    id          BIGSERIAL   PRIMARY KEY,
    depot_id    BIGINT      NOT NULL,
    plan_date   DATE        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'TASLAK',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plans_depot_date UNIQUE (depot_id, plan_date),
    CONSTRAINT fk_plans_depot      FOREIGN KEY (depot_id)   REFERENCES depots (id) ON DELETE RESTRICT,
    CONSTRAINT fk_plans_creator    FOREIGN KEY (created_by) REFERENCES users (id)  ON DELETE SET NULL,
    CONSTRAINT ck_plans_status     CHECK (status IN ('TASLAK', 'SEVK_EDILDI', 'TAMAMLANDI'))
);
--rollback DROP TABLE delivery_plans;

--changeset routewise:005-optimization-runs
--comment Her algoritma koşusu bir satır — karşılaştırma verisinin kaynağı
CREATE TABLE optimization_runs (
    id                  BIGSERIAL     PRIMARY KEY,
    plan_id             BIGINT        NOT NULL,
    algorithm           VARCHAR(40)   NOT NULL,
    is_applied          BOOLEAN       NOT NULL DEFAULT FALSE,
    is_baseline         BOOLEAN       NOT NULL DEFAULT FALSE,
    total_distance_m    NUMERIC(12,2),
    total_duration_s    INTEGER,
    total_fuel_l        NUMERIC(10,3),
    total_cost_try      NUMERIC(12,2),
    vehicle_count       SMALLINT,
    computation_time_ms INTEGER,
    random_seed         BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_runs_plan      FOREIGN KEY (plan_id) REFERENCES delivery_plans (id) ON DELETE RESTRICT,
    CONSTRAINT ck_runs_algorithm CHECK (algorithm IN ('NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE', 'DFS_EXHAUSTIVE'))
);
-- Bütünlük kuralı: bir plan altında en fazla bir koşu uygulanmış olabilir.
CREATE UNIQUE INDEX uq_runs_one_applied ON optimization_runs (plan_id) WHERE is_applied;
CREATE INDEX ix_runs_plan      ON optimization_runs (plan_id);
CREATE INDEX ix_runs_algorithm ON optimization_runs (algorithm);
-- Haftalık/aylık/yıllık agregasyonlar bu indeksi kullanır.
CREATE INDEX ix_runs_created   ON optimization_runs (created_at);
--rollback DROP TABLE optimization_runs;

--changeset routewise:005-routes
--comment Araç başına rota
CREATE TABLE routes (
    id               BIGSERIAL     PRIMARY KEY,
    run_id           BIGINT        NOT NULL,
    vehicle_id       BIGINT        NOT NULL,
    courier_id       BIGINT,
    total_distance_m NUMERIC(12,2),
    total_duration_s INTEGER,
    total_load_kg    NUMERIC(8,2),
    status           VARCHAR(20)   NOT NULL DEFAULT 'PLANLANDI',
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT fk_routes_run     FOREIGN KEY (run_id)     REFERENCES optimization_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_routes_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)          ON DELETE RESTRICT,
    CONSTRAINT fk_routes_courier FOREIGN KEY (courier_id) REFERENCES couriers (id)          ON DELETE SET NULL,
    CONSTRAINT ck_routes_status  CHECK (status IN ('PLANLANDI', 'YOLDA', 'TAMAMLANDI'))
);
CREATE INDEX ix_routes_run     ON routes (run_id);
CREATE INDEX ix_routes_courier ON routes (courier_id);
--rollback DROP TABLE routes;

--changeset routewise:005-route-stops
--comment Rota içindeki sıralı duraklar
CREATE TABLE route_stops (
    id                   BIGSERIAL     PRIMARY KEY,
    route_id             BIGINT        NOT NULL,
    shipment_id          BIGINT        NOT NULL,
    sequence_no          INTEGER       NOT NULL,
    distance_from_prev_m NUMERIC(10,2),
    duration_from_prev_s INTEGER,
    eta                  TIMESTAMPTZ,
    actual_arrival       TIMESTAMPTZ,
    status               VARCHAR(20)   NOT NULL DEFAULT 'BEKLEMEDE',
    CONSTRAINT uq_stops_route_seq  UNIQUE (route_id, sequence_no),
    CONSTRAINT fk_stops_route      FOREIGN KEY (route_id)    REFERENCES routes (id)    ON DELETE CASCADE,
    CONSTRAINT fk_stops_shipment   FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stops_sequence   CHECK (sequence_no >= 0),
    CONSTRAINT ck_stops_status     CHECK (status IN ('BEKLEMEDE', 'TESLIM_EDILDI', 'ULASILAMADI'))
);
CREATE INDEX ix_stops_route    ON route_stops (route_id);
CREATE INDEX ix_stops_shipment ON route_stops (shipment_id);
--rollback DROP TABLE route_stops;
