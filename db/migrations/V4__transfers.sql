-- V4: transferência manual entre máquinas (M3): itens da fila/histórico e conjuntos de filtros salvos

CREATE TABLE transfer_item (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id          BIGINT        NOT NULL REFERENCES app_user (id),
    batch_id          UUID          NOT NULL,
    -- COPY (transferir), MKDIR (criar diretório), RENAME, DELETE
    op                VARCHAR(10)   NOT NULL DEFAULT 'COPY',
    -- tipo do item de origem: FILE, DIR ou SYMLINK
    kind              VARCHAR(10)   NOT NULL DEFAULT 'FILE',
    status            VARCHAR(15)   NOT NULL,
    src_machine_id    BIGINT        REFERENCES machine (id) ON DELETE SET NULL,
    src_machine_name  VARCHAR(100),
    src_path          VARCHAR(1500),
    dst_machine_id    BIGINT        REFERENCES machine (id) ON DELETE SET NULL,
    dst_machine_name  VARCHAR(100),
    dst_path          VARCHAR(1500) NOT NULL,
    size              BIGINT        NOT NULL DEFAULT 0,
    bytes_done        BIGINT        NOT NULL DEFAULT 0,
    -- retomada: tamanho e data da origem no início e deslocamento já gravado no temporário
    src_mtime         BIGINT,
    src_size_start    BIGINT,
    src_mode          INTEGER,
    link_target       VARCHAR(1500),
    resume_offset     BIGINT        NOT NULL DEFAULT 0,
    tmp_path          VARCHAR(1600),
    -- RESUME em conflito: continua o próprio arquivo final do destino
    append_final      BOOLEAN       NOT NULL DEFAULT FALSE,
    conflict_action   VARCHAR(25),
    skip_reason       VARCHAR(100),
    note              VARCHAR(200),
    error_code        VARCHAR(100),
    error_params      JSONB,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    CONSTRAINT ck_transfer_op CHECK (op IN ('COPY', 'MKDIR', 'RENAME', 'DELETE')),
    CONSTRAINT ck_transfer_kind CHECK (kind IN ('FILE', 'DIR', 'SYMLINK')),
    CONSTRAINT ck_transfer_status CHECK (status IN
        ('QUEUED', 'TRANSFERRING', 'COMPLETED', 'FAILED', 'CANCELED', 'SKIPPED', 'INTERRUPTED'))
);
CREATE INDEX ix_transfer_owner ON transfer_item (owner_id, id DESC);
CREATE INDEX ix_transfer_status ON transfer_item (status, id);
CREATE INDEX ix_transfer_batch ON transfer_item (batch_id);

CREATE TABLE transfer_filter_set (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id    BIGINT       NOT NULL REFERENCES app_user (id),
    name        VARCHAR(100) NOT NULL,
    definition  JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_transfer_filter_owner_name ON transfer_filter_set (owner_id, lower(name));
