--liquibase formatted sql

-- Kargolar. Sipariş numarası sistem tarafından üretilir (BND-YYYY-NNNNN);
-- elle giriş yoktur, yanlış yazma riski sıfırdır.

--changeset routewise:004-shipment-number-seq
--comment Sipariş numarası sayacı
CREATE SEQUENCE shipment_number_seq START WITH 1 INCREMENT BY 1;
--rollback DROP SEQUENCE shipment_number_seq;

--changeset routewise:004-shipments
--comment Kargo kayıtları
CREATE TABLE shipments (
    id                BIGSERIAL     PRIMARY KEY,
    order_number      VARCHAR(20)   NOT NULL,
    recipient_id      BIGINT        NOT NULL,
    address_id        BIGINT        NOT NULL,
    weight_kg         NUMERIC(8,2)  NOT NULL,
    volume_m3         NUMERIC(8,2),
    priority          SMALLINT      NOT NULL DEFAULT 0,
    service_time_min  INTEGER       NOT NULL DEFAULT 5,
    status            VARCHAR(20)   NOT NULL DEFAULT 'BEKLEMEDE',
    postponed_to      DATE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT uq_shipments_order     UNIQUE (order_number),
    CONSTRAINT fk_shipments_recipient FOREIGN KEY (recipient_id) REFERENCES recipients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipments_address   FOREIGN KEY (address_id)   REFERENCES addresses (id)  ON DELETE RESTRICT,
    CONSTRAINT ck_shipments_weight    CHECK (weight_kg > 0),
    CONSTRAINT ck_shipments_priority  CHECK (priority BETWEEN 0 AND 10),
    CONSTRAINT ck_shipments_service   CHECK (service_time_min >= 0),
    CONSTRAINT ck_shipments_status    CHECK (status IN ('BEKLEMEDE', 'PLANLANDI', 'YOLDA', 'TESLIM_EDILDI', 'ERTELENDI')),
    -- Ertelenen kargo hangi güne ertelendiğini taşımalıdır.
    CONSTRAINT ck_shipments_postponed CHECK (status <> 'ERTELENDI' OR postponed_to IS NOT NULL)
);
CREATE INDEX ix_shipments_status    ON shipments (status);
CREATE INDEX ix_shipments_recipient ON shipments (recipient_id);
CREATE INDEX ix_shipments_address   ON shipments (address_id);
--rollback DROP TABLE shipments;
