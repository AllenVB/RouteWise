--liquibase formatted sql

-- Kimlik doğrulama: refresh token saklama ve seed kullanıcılarının gerçek
-- parola hash'leri.

--changeset routewise:010-refresh-tokens
--comment Refresh token'lar — çıkışta gerçekten geçersizlenebilsin diye saklanır
CREATE TABLE refresh_tokens (
    id          BIGSERIAL   PRIMARY KEY,
    jti         UUID        NOT NULL,
    user_id     BIGINT      NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_jti UNIQUE (jti),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_refresh_user   ON refresh_tokens (user_id);
-- Süresi dolmuş kayıtların temizliği bu indeksi kullanır.
CREATE INDEX ix_refresh_expiry ON refresh_tokens (expires_at);
--rollback DROP TABLE refresh_tokens;

--changeset routewise:010-seed-passwords context:!test
--comment Yerel geliştirme hesaplarının parolası
--
-- Parola (yalnızca yerel geliştirme): RouteWise!Dev2026
-- Üç hesap da aynı parolayı kullanır. Bu değerler localhost dışında
-- kullanılmaz; gerçek kurulumda ilk açılışta değiştirilmelidir.
UPDATE users SET password_hash = '$2b$10$SErlzQUQ1YcvkWzI2uGyD.O62k6C5uO.Ouemgs8Okk639V1K3csji'
WHERE email = 'firma@routewise.local';
UPDATE users SET password_hash = '$2b$10$09GhKsRJeewMxklihv9P8e0ViYJjqGEpIHcqyuGoA8Pr4AL4.sUb2'
WHERE email = 'kurye@routewise.local';
UPDATE users SET password_hash = '$2b$10$XNz6gnll/9mPnAC2bhvsXu5LykcYahlxRtNhSXTMSt.95S3PXnk1y'
WHERE email = 'alici@routewise.local';
--rollback UPDATE users SET password_hash = 'DEGISTIRILECEK' WHERE email LIKE '%@routewise.local';
