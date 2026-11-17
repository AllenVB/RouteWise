--liquibase formatted sql

-- Faz 5 — yolda yeniden rotalama. Sevk edilmiş bir rotadan çıkan kargo (alıcı
-- erteledi ya da güne sığmadı) durak satırını silmez: kuryeye ve rapora "bu durak
-- vardı, ertelendi" bilgisi kalır. Açık duraklar yeniden sıralanırken sıra
-- numaraları yer değiştirir; tekillik kuralı bu yüzden transaction sonunda denetlenir.

--changeset routewise:034-stop-postponed
--comment Durak durumu: rotadan ertelenerek çıkarıldı
ALTER TABLE route_stops DROP CONSTRAINT ck_stops_status;
ALTER TABLE route_stops ADD CONSTRAINT ck_stops_status
    CHECK (status IN ('BEKLEMEDE', 'TESLIM_EDILDI', 'ULASILAMADI', 'ERTELENDI'));
--rollback ALTER TABLE route_stops DROP CONSTRAINT ck_stops_status; ALTER TABLE route_stops ADD CONSTRAINT ck_stops_status CHECK (status IN ('BEKLEMEDE', 'TESLIM_EDILDI', 'ULASILAMADI'));

--changeset routewise:034-stop-sequence-deferred
--comment Yeniden sıralamada 3<->4 takası ara adımda çakışmasın
ALTER TABLE route_stops DROP CONSTRAINT uq_stops_route_seq;
ALTER TABLE route_stops ADD CONSTRAINT uq_stops_route_seq
    UNIQUE (route_id, sequence_no) DEFERRABLE INITIALLY DEFERRED;
--rollback ALTER TABLE route_stops DROP CONSTRAINT uq_stops_route_seq; ALTER TABLE route_stops ADD CONSTRAINT uq_stops_route_seq UNIQUE (route_id, sequence_no);

--changeset routewise:034-reroute-events
--comment Her yeniden rotalama bir satır — dinamik değişikliklerin ölçüm verisi
CREATE TABLE reroute_events (
    id                  BIGSERIAL     PRIMARY KEY,
    route_id            BIGINT        NOT NULL,
    trigger_type        VARCHAR(30)   NOT NULL,
    shipment_id         BIGINT,
    start_node_id       BIGINT        NOT NULL,
    open_stops_before   INTEGER       NOT NULL,
    open_stops_after    INTEGER       NOT NULL,
    auto_postponed      INTEGER       NOT NULL DEFAULT 0,
    -- Eski sırayı koruyarak (yalnızca çıkan durak atlanarak) kalan turun maliyeti;
    -- naive baseline budur. Güne sığmıyorsa kurulamaz ve boş kalır.
    kept_order_cost_try NUMERIC(12,2),
    new_cost_try        NUMERIC(12,2),
    computation_time_ms INTEGER,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_reroute_route    FOREIGN KEY (route_id)    REFERENCES routes (id)    ON DELETE CASCADE,
    CONSTRAINT fk_reroute_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE SET NULL,
    CONSTRAINT ck_reroute_trigger  CHECK (trigger_type IN ('ERTELEME', 'ADRES_DEGISIKLIGI')),
    CONSTRAINT ck_reroute_counts   CHECK (open_stops_before >= 0 AND open_stops_after >= 0 AND auto_postponed >= 0)
);
CREATE INDEX ix_reroute_route   ON reroute_events (route_id);
-- Günlük değişiklik limiti ve haftalık/aylık analiz bu indeksi kullanır.
CREATE INDEX ix_reroute_created ON reroute_events (created_at);
--rollback DROP TABLE reroute_events;
