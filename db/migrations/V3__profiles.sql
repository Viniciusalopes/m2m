-- V3: perfis, agendamentos, backups, restaurações, sincronizações e chaves de perfil (M1)

CREATE TABLE profile (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id            BIGINT        NOT NULL REFERENCES app_user (id),
    name                VARCHAR(100)  NOT NULL,
    complement          VARCHAR(60),
    source_machine_id   BIGINT        NOT NULL REFERENCES machine (id) ON DELETE RESTRICT,
    storage_machine_id  BIGINT        NOT NULL REFERENCES machine (id) ON DELETE RESTRICT,
    storage_dir         VARCHAR(1000) NOT NULL,
    suspect_threshold   NUMERIC(4, 2) NOT NULL DEFAULT 0.50,
    encryption_enabled  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_profile_threshold CHECK (suspect_threshold > 0 AND suspect_threshold <= 1)
);
CREATE UNIQUE INDEX uq_profile_owner_name ON profile (owner_id, lower(name));

CREATE TABLE profile_dir (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id  BIGINT        NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    position    INTEGER       NOT NULL,
    path        VARCHAR(1000) NOT NULL,
    CONSTRAINT uq_profile_dir_position UNIQUE (profile_id, position)
);

CREATE TABLE profile_dir_mapping (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_dir_id  BIGINT        NOT NULL REFERENCES profile_dir (id) ON DELETE CASCADE,
    machine_id      BIGINT        NOT NULL REFERENCES machine (id) ON DELETE RESTRICT,
    path            VARCHAR(1000) NOT NULL,
    CONSTRAINT uq_profile_dir_mapping UNIQUE (profile_dir_id, machine_id)
);

CREATE TABLE backup_schedule (
    profile_id   BIGINT PRIMARY KEY REFERENCES profile (id) ON DELETE CASCADE,
    time_of_day  TIME    NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE sync_schedule (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id            BIGINT  NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    dest_machine_id       BIGINT  NOT NULL REFERENCES machine (id) ON DELETE RESTRICT,
    time_of_day           TIME    NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    max_deletions_percent INTEGER NOT NULL DEFAULT 20,
    max_deletions_abs     INTEGER NOT NULL DEFAULT 500,
    CONSTRAINT uq_sync_schedule UNIQUE (profile_id, dest_machine_id)
);

-- O histórico guarda o nome que o perfil/máquina tinha: sobrevive à remoção deles.
ALTER TABLE job ADD COLUMN profile_name VARCHAR(100);
ALTER TABLE job ADD CONSTRAINT fk_job_profile FOREIGN KEY (profile_id) REFERENCES profile (id) ON DELETE SET NULL;

CREATE TABLE backup_archive (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id                    BIGINT        NOT NULL REFERENCES app_user (id),
    profile_id                  BIGINT        REFERENCES profile (id) ON DELETE SET NULL,
    profile_name_snapshot       VARCHAR(100)  NOT NULL,
    type                        VARCHAR(20)   NOT NULL DEFAULT 'REGULAR',
    status                      VARCHAR(30)   NOT NULL,
    accepted                    BOOLEAN       NOT NULL DEFAULT FALSE,
    pinned                      BOOLEAN       NOT NULL DEFAULT FALSE,
    storage_machine_id          BIGINT        REFERENCES machine (id) ON DELETE SET NULL,
    storage_machine_name        VARCHAR(100),
    storage_dir                 VARCHAR(1000) NOT NULL,
    file_name                   VARCHAR(500)  NOT NULL,
    source_machine_name         VARCHAR(100),
    source_protocol             VARCHAR(10),
    full_fidelity               BOOLEAN       NOT NULL DEFAULT TRUE,
    item_count                  BIGINT        NOT NULL DEFAULT 0,
    file_count                  BIGINT        NOT NULL DEFAULT 0,
    total_bytes                 BIGINT        NOT NULL DEFAULT 0,
    archive_bytes               BIGINT        NOT NULL DEFAULT 0,
    sha256                      VARCHAR(64),
    encrypted                   BOOLEAN       NOT NULL DEFAULT FALSE,
    mapping                     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    warnings                    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    error                       TEXT,
    job_id                      BIGINT        REFERENCES job (id) ON DELETE SET NULL,
    dest_machine_name           VARCHAR(100),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at                 TIMESTAMPTZ,
    CONSTRAINT ck_backup_archive_type CHECK (type IN ('REGULAR', 'PRE_RESTORE', 'PRE_SYNC'))
);
CREATE INDEX ix_backup_archive_profile ON backup_archive (profile_id, type, created_at DESC);
CREATE INDEX ix_backup_archive_owner ON backup_archive (owner_id, created_at DESC);

CREATE TABLE restore_run (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id              BIGINT       NOT NULL REFERENCES app_user (id),
    job_id                BIGINT       REFERENCES job (id) ON DELETE SET NULL,
    archive_id            BIGINT       REFERENCES backup_archive (id) ON DELETE SET NULL,
    archive_name          VARCHAR(500),
    dest_machine_id       BIGINT       REFERENCES machine (id) ON DELETE SET NULL,
    dest_machine_name     VARCHAR(100),
    selective             BOOLEAN      NOT NULL DEFAULT FALSE,
    paths                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status                VARCHAR(30)  NOT NULL,
    counts                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    pre_restore_archive_id BIGINT      REFERENCES backup_archive (id) ON DELETE SET NULL,
    undo_of_id            BIGINT       REFERENCES restore_run (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ
);

CREATE TABLE sync_run (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id            BIGINT       NOT NULL REFERENCES app_user (id),
    job_id              BIGINT       REFERENCES job (id) ON DELETE SET NULL,
    profile_id          BIGINT       REFERENCES profile (id) ON DELETE SET NULL,
    profile_name        VARCHAR(100),
    dest_machine_id     BIGINT       REFERENCES machine (id) ON DELETE SET NULL,
    dest_machine_name   VARCHAR(100),
    trigger             VARCHAR(10)  NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    plan                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    counts              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    pre_sync_archive_id BIGINT       REFERENCES backup_archive (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ
);

-- Estado do destino após a última sincronização concluída (proteção do trabalho feito no destino, D24)
CREATE TABLE sync_state (
    profile_id       BIGINT       NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    dest_machine_id  BIGINT       NOT NULL REFERENCES machine (id) ON DELETE CASCADE,
    state            BYTEA        NOT NULL,
    item_count       BIGINT       NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, dest_machine_id)
);

CREATE TABLE profile_key (
    profile_id  BIGINT PRIMARY KEY REFERENCES profile (id) ON DELETE CASCADE,
    key_id      VARCHAR(64) NOT NULL,
    key_enc     BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
