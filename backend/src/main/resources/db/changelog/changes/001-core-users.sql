--liquibase formatted sql

-- Kullanıcı ve rol profilleri.
-- Rol bazlı ek alanlar ayrı profil tablolarında tutulur; tek tabloda null
-- alan yığılmaz.

--changeset routewise:001-users
--comment Ortak kullanıcı tablosu — üç rol
CREATE TABLE users (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(150) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(20)  NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role  CHECK (role IN ('COMPANY', 'COURIER', 'RECIPIENT'))
);
CREATE INDEX ix_users_role ON users (role);
--rollback DROP TABLE users;

--changeset routewise:001-couriers
--comment Kurye profili — users ile 1:1
CREATE TABLE couriers (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    employee_code   VARCHAR(30),
    status          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_couriers_user     UNIQUE (user_id),
    CONSTRAINT uq_couriers_code     UNIQUE (employee_code),
    CONSTRAINT fk_couriers_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_couriers_status   CHECK (status IN ('AVAILABLE', 'ON_ROUTE', 'OFF_DUTY'))
);
--rollback DROP TABLE couriers;

--changeset routewise:001-recipients
--comment Alıcı profili — users ile 1:1
CREATE TABLE recipients (
    id                  BIGSERIAL   PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    default_address_id  BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recipients_user UNIQUE (user_id),
    CONSTRAINT fk_recipients_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
--rollback DROP TABLE recipients;
