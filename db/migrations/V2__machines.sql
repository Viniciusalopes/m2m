-- V2: máquinas, identidade SSH do usuário e identidade confirmada dos servidores (M1)

CREATE TABLE machine (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id         BIGINT       NOT NULL REFERENCES app_user (id),
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500),
    host             VARCHAR(255) NOT NULL,
    protocol         VARCHAR(10)  NOT NULL,
    port             INTEGER      NOT NULL,
    ftps_mode        VARCHAR(10),
    username         VARCHAR(100),
    auth_type        VARCHAR(10),
    password_enc     BYTEA,
    max_connections  INTEGER      NOT NULL DEFAULT 4,
    capabilities     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_test_at     TIMESTAMPTZ,
    last_test        JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_machine_owner_name UNIQUE (owner_id, name),
    CONSTRAINT ck_machine_protocol CHECK (protocol IN ('LOCAL', 'SFTP', 'FTP', 'FTPS')),
    CONSTRAINT ck_machine_ftps_mode CHECK (ftps_mode IS NULL OR ftps_mode IN ('EXPLICIT', 'IMPLICIT')),
    CONSTRAINT ck_machine_auth CHECK (auth_type IS NULL OR auth_type IN ('KEY', 'PASSWORD')),
    CONSTRAINT ck_machine_max_conn CHECK (max_connections BETWEEN 1 AND 32)
);
-- Só existe uma máquina LOCAL: o host onde o m2m roda
CREATE UNIQUE INDEX uq_machine_single_local ON machine ((protocol)) WHERE protocol = 'LOCAL';

CREATE TABLE ssh_identity (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id         BIGINT      NOT NULL UNIQUE REFERENCES app_user (id),
    algorithm        VARCHAR(20) NOT NULL,
    public_key       TEXT        NOT NULL,
    private_key_enc  BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE server_identity (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    machine_id           BIGINT      NOT NULL UNIQUE REFERENCES machine (id) ON DELETE CASCADE,
    kind                 VARCHAR(10) NOT NULL,
    fingerprint          VARCHAR(200),
    confirmed_at         TIMESTAMPTZ,
    pending_fingerprint  VARCHAR(200),
    pending_seen_at      TIMESTAMPTZ,
    CONSTRAINT ck_server_identity_kind CHECK (kind IN ('SSH', 'TLS'))
);
