--liquibase formatted sql

-- Faz 5 — maliyet artışı eşiği. Firmanın onayladığı adres değişikliği, kurye o
-- bölgeyi çoktan geçmişken rotayı pahalılaştırabilir. Yeniden rotalama durağın
-- ek maliyetini ölçer (durak varken ve yokken kalan turun TL maliyeti); oran eşiği
-- aşarsa firmaya "yarına ertele" önerisi düşer. Karar firmanındır: erteler ya da
-- bugün kalsın der. Bir durağın aynı anda tek açık önerisi olur.

--changeset routewise:036-postpone-suggestions
--comment Maliyet eşiğini aşan adres değişikliği için erteleme önerisi
CREATE TABLE postpone_suggestions (
    id               BIGSERIAL     PRIMARY KEY,
    route_id         BIGINT        NOT NULL,
    stop_id          BIGINT        NOT NULL,
    shipment_id      BIGINT        NOT NULL,
    reroute_event_id BIGINT,
    -- Kalan turun maliyeti: taşınan durak yokken / varken (öncelik cezası hariç TL).
    cost_without_try NUMERIC(12,2) NOT NULL,
    cost_with_try    NUMERIC(12,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACIK',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    decided_by       BIGINT,
    CONSTRAINT fk_suggestion_route    FOREIGN KEY (route_id)         REFERENCES routes (id)         ON DELETE CASCADE,
    CONSTRAINT fk_suggestion_stop     FOREIGN KEY (stop_id)          REFERENCES route_stops (id)    ON DELETE CASCADE,
    CONSTRAINT fk_suggestion_shipment FOREIGN KEY (shipment_id)      REFERENCES shipments (id)      ON DELETE CASCADE,
    CONSTRAINT fk_suggestion_event    FOREIGN KEY (reroute_event_id) REFERENCES reroute_events (id) ON DELETE SET NULL,
    CONSTRAINT fk_suggestion_user     FOREIGN KEY (decided_by)       REFERENCES users (id)          ON DELETE SET NULL,
    CONSTRAINT ck_suggestion_status   CHECK (status IN ('ACIK', 'ERTELENDI', 'KALDI', 'GECERSIZ')),
    CONSTRAINT ck_suggestion_costs    CHECK (cost_without_try >= 0 AND cost_with_try >= 0)
);
CREATE UNIQUE INDEX ux_suggestion_open_stop ON postpone_suggestions (stop_id) WHERE status = 'ACIK';
CREATE INDEX ix_suggestion_route ON postpone_suggestions (route_id);
--rollback DROP TABLE postpone_suggestions;
