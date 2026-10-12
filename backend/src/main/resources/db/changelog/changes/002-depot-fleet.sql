--liquibase formatted sql

-- Depo ve araç filosu.
-- Kapsam tek depodur, ancak tablo çoklu depoya açıktır.

--changeset routewise:002-depots
--comment Dağıtım merkezi
CREATE TABLE depots (
    id                      BIGSERIAL    PRIMARY KEY,
    name                    VARCHAR(120) NOT NULL,
    address_text            VARCHAR(500),
    location                GEOMETRY(POINT, 4326) NOT NULL,
    snapped_node_id         BIGINT,
    opening_time            TIME         NOT NULL DEFAULT '08:00',
    closing_time            TIME         NOT NULL DEFAULT '18:00',
    max_route_duration_min  INTEGER      NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_depots_duration CHECK (max_route_duration_min > 0)
);
CREATE INDEX ix_depots_location ON depots USING GIST (location);
--rollback DROP TABLE depots;

--changeset routewise:002-vehicles
--comment Araç filosu — şimdilik seed ile yüklenir, yönetim ekranı yok
CREATE TABLE vehicles (
    id                  BIGSERIAL     PRIMARY KEY,
    depot_id            BIGINT        NOT NULL,
    plate               VARCHAR(20)   NOT NULL,
    capacity_kg         NUMERIC(8,2)  NOT NULL,
    capacity_volume_m3  NUMERIC(8,2),
    fuel_l_per_100km    NUMERIC(5,2)  NOT NULL,
    fuel_type           VARCHAR(20)   NOT NULL DEFAULT 'DIESEL',
    fixed_cost_per_day  NUMERIC(10,2) NOT NULL DEFAULT 0,
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_vehicles_plate   UNIQUE (plate),
    CONSTRAINT fk_vehicles_depot   FOREIGN KEY (depot_id) REFERENCES depots (id) ON DELETE RESTRICT,
    CONSTRAINT ck_vehicles_cap     CHECK (capacity_kg > 0),
    CONSTRAINT ck_vehicles_fuel    CHECK (fuel_l_per_100km > 0),
    CONSTRAINT ck_vehicles_type    CHECK (fuel_type IN ('DIESEL', 'GASOLINE', 'ELECTRIC'))
);
CREATE INDEX ix_vehicles_depot ON vehicles (depot_id) WHERE is_active;
--rollback DROP TABLE vehicles;
