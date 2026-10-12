--liquibase formatted sql

-- Destek tabloları: bildirim, maliyet parametreleri, matris cache,
-- yol bulma karşılaştırma kayıtları.

--changeset routewise:006-notifications
--comment Alıcı bildirimleri — uygulama içi + e-posta
CREATE TABLE notifications (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    shipment_id BIGINT,
    type        VARCHAR(30) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    payload     JSONB,
    sent_at     TIMESTAMPTZ,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notif_user     FOREIGN KEY (user_id)     REFERENCES users (id)     ON DELETE CASCADE,
    CONSTRAINT fk_notif_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE CASCADE,
    CONSTRAINT ck_notif_type     CHECK (type IN ('ETA', 'ERTELEME', 'DOGRULAMA', 'ROTA_DEGISTI')),
    CONSTRAINT ck_notif_channel  CHECK (channel IN ('IN_APP', 'EMAIL'))
);
CREATE INDEX ix_notif_user_unread ON notifications (user_id) WHERE read_at IS NULL;
--rollback DROP TABLE notifications;

--changeset routewise:006-cost-parameters
--comment Maliyet parametreleri — tarihli, geçmiş koşuların maliyeti bozulmasın
CREATE TABLE cost_parameters (
    id                   BIGSERIAL     PRIMARY KEY,
    fuel_price_per_l     NUMERIC(8,2)  NOT NULL,
    driver_cost_per_hour NUMERIC(8,2)  NOT NULL,
    priority_penalty     NUMERIC(8,2)  NOT NULL DEFAULT 0,
    effective_from       DATE          NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_effective UNIQUE (effective_from),
    CONSTRAINT ck_cost_fuel      CHECK (fuel_price_per_l > 0),
    CONSTRAINT ck_cost_driver    CHECK (driver_cost_per_hour >= 0),
    CONSTRAINT ck_cost_penalty   CHECK (priority_penalty >= 0)
);
--rollback DROP TABLE cost_parameters;

--changeset routewise:006-distance-matrix-cache
--comment Mesafe/süre matrisi cache — bu olmadan algoritma ayarı yapılamaz
CREATE TABLE distance_matrix_cache (
    id            BIGSERIAL     PRIMARY KEY,
    node_set_hash CHAR(64)      NOT NULL,
    from_node_id  BIGINT        NOT NULL,
    to_node_id    BIGINT        NOT NULL,
    distance_m    NUMERIC(10,2) NOT NULL,
    duration_s    INTEGER       NOT NULL,
    computed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_matrix_cell UNIQUE (node_set_hash, from_node_id, to_node_id),
    CONSTRAINT ck_matrix_dist CHECK (distance_m >= 0),
    CONSTRAINT ck_matrix_dur  CHECK (duration_s >= 0)
);
CREATE INDEX ix_matrix_hash ON distance_matrix_cache (node_set_hash);
--rollback DROP TABLE distance_matrix_cache;

--changeset routewise:006-path-comparison-runs
--comment Seviye 1 (yol bulma) deney kayıtları — Dijkstra/A*/BFS/DFS
CREATE TABLE path_comparison_runs (
    id                  BIGSERIAL     PRIMARY KEY,
    algorithm           VARCHAR(20)   NOT NULL,
    from_node_id        BIGINT        NOT NULL,
    to_node_id          BIGINT        NOT NULL,
    path_length_m       NUMERIC(10,2),
    computation_time_ms NUMERIC(10,3),
    nodes_visited       INTEGER,
    deviation_pct       NUMERIC(6,2),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_path_algorithm CHECK (algorithm IN ('DIJKSTRA', 'ASTAR', 'BFS', 'DFS'))
);
CREATE INDEX ix_path_algorithm ON path_comparison_runs (algorithm);
--rollback DROP TABLE path_comparison_runs;
