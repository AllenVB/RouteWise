--liquibase formatted sql

-- Adresler — projenin iş kuralı en yoğun tablosu.
-- Durum makinesi: TASLAK -> DOGRULAMA_BEKLIYOR -> DOGRULANDI / REDDEDILDI
-- Yalnızca DOGRULANDI adresler optimizasyona girer.

--changeset routewise:003-addresses
--comment Alıcı adresleri, doğrulama durumu ve snap bilgisiyle
CREATE TABLE addresses (
    id                  BIGSERIAL     PRIMARY KEY,
    recipient_id        BIGINT        NOT NULL,
    address_text        VARCHAR(500)  NOT NULL,
    district            VARCHAR(100),
    neighbourhood       VARCHAR(100),
    location            GEOMETRY(POINT, 4326) NOT NULL,
    snapped_node_id     BIGINT,
    snap_distance_m     NUMERIC(8,2),
    verification_status VARCHAR(20)   NOT NULL DEFAULT 'TASLAK',
    verified_by         BIGINT,
    verified_at         TIMESTAMPTZ,
    rejection_reason    VARCHAR(300),
    change_count_today  SMALLINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT fk_addresses_recipient FOREIGN KEY (recipient_id) REFERENCES recipients (id) ON DELETE CASCADE,
    CONSTRAINT fk_addresses_verifier  FOREIGN KEY (verified_by)  REFERENCES users (id)      ON DELETE SET NULL,
    CONSTRAINT ck_addresses_status    CHECK (verification_status IN ('TASLAK', 'DOGRULAMA_BEKLIYOR', 'DOGRULANDI', 'REDDEDILDI')),
    -- Doğrulanmış adres kimin onayladığını ve ne zaman onaylandığını taşımalıdır.
    CONSTRAINT ck_addresses_verified  CHECK (
        verification_status <> 'DOGRULANDI'
        OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)
    ),
    -- Reddedilen adres gerekçe taşımalıdır.
    CONSTRAINT ck_addresses_rejected  CHECK (
        verification_status <> 'REDDEDILDI' OR rejection_reason IS NOT NULL
    ),
    CONSTRAINT ck_addresses_changes   CHECK (change_count_today >= 0)
);
CREATE INDEX ix_addresses_location  ON addresses USING GIST (location);
CREATE INDEX ix_addresses_recipient ON addresses (recipient_id);
-- Doğrulama kuyruğu ekranı bu indeksi kullanır.
CREATE INDEX ix_addresses_pending   ON addresses (verification_status)
    WHERE verification_status = 'DOGRULAMA_BEKLIYOR';
--rollback DROP TABLE addresses;

--changeset routewise:003-recipients-default-address-fk
--comment recipients.default_address_id -> addresses (döngüsel bağımlılık nedeniyle ayrı)
ALTER TABLE recipients
    ADD CONSTRAINT fk_recipients_default_address
    FOREIGN KEY (default_address_id) REFERENCES addresses (id) ON DELETE SET NULL;
--rollback ALTER TABLE recipients DROP CONSTRAINT fk_recipients_default_address;
