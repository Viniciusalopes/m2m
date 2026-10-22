-- V1: usuários, auditoria, notificações, configuração e fila de jobs (M0)

CREATE TABLE app_user (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username         VARCHAR(100) NOT NULL UNIQUE,
    email            VARCHAR(255) NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    role             VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE login_attempt (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    ip            VARCHAR(64),
    success       BOOLEAN      NOT NULL,
    reason        VARCHAR(40),
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_attempt_user_time ON login_attempt (username, attempted_at DESC);

CREATE TABLE user_totp (
    user_id         BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    secret_enc      BYTEA        NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    recovery_codes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id     BIGINT       REFERENCES app_user (id),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    username     VARCHAR(100),
    ip           VARCHAR(64),
    action       VARCHAR(60)  NOT NULL,
    target       VARCHAR(500),
    result       VARCHAR(20)  NOT NULL,
    details      JSONB        NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_event_time ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_event_action ON audit_event (action, occurred_at DESC);

-- Somente inclusão: nenhuma alteração; exclusão só de eventos com mais de 365 dias (limpeza diária).
CREATE FUNCTION audit_event_somente_inclusao() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_event não pode ser alterado';
    END IF;
    IF TG_OP = 'DELETE' AND OLD.occurred_at > now() - INTERVAL '365 days' THEN
        RAISE EXCEPTION 'audit_event só pode ser excluído após 365 dias';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_event_somente_inclusao
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_somente_inclusao();

CREATE TABLE notification (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES app_user (id),
    type          VARCHAR(60)  NOT NULL,
    severity      VARCHAR(10)  NOT NULL,
    message_code  VARCHAR(100) NOT NULL,
    params        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    target_type   VARCHAR(40),
    target_id     BIGINT,
    read          BOOLEAN      NOT NULL DEFAULT FALSE,
    email_status  VARCHAR(20),
    email_error   VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_owner ON notification (owner_id, read, created_at DESC);

CREATE TABLE user_smtp_setting (
    owner_id      BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    host          VARCHAR(255) NOT NULL,
    port          INTEGER      NOT NULL,
    security      VARCHAR(10)  NOT NULL DEFAULT 'STARTTLS',
    username      VARCHAR(255),
    password_enc  BYTEA,
    from_address  VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_smtp_security CHECK (security IN ('NONE', 'STARTTLS', 'SSL'))
);

CREATE TABLE app_setting (
    key         VARCHAR(100) PRIMARY KEY,
    value       JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE job (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id     BIGINT       NOT NULL REFERENCES app_user (id),
    type         VARCHAR(30)  NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    step         VARCHAR(60),
    progress     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    trigger      VARCHAR(10)  NOT NULL,
    profile_id   BIGINT,
    params       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result       JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    CONSTRAINT ck_job_trigger CHECK (trigger IN ('MANUAL', 'SCHEDULED', 'SYSTEM'))
);
CREATE INDEX ix_job_owner_status ON job (owner_id, status);
CREATE INDEX ix_job_created ON job (created_at DESC);

CREATE TABLE job_message (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id    BIGINT       NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    level     VARCHAR(10)  NOT NULL,
    code      VARCHAR(100) NOT NULL,
    params    JSONB        NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_job_message_job ON job_message (job_id, at);

-- Outbox: criação do job/item e publicação pendente na mesma transação (D9)
CREATE TABLE job_outbox (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          VARCHAR(10)  NOT NULL,
    ref_id        BIGINT       NOT NULL,
    exchange      VARCHAR(100) NOT NULL,
    routing_key   VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    CONSTRAINT ck_outbox_kind CHECK (kind IN ('JOB', 'TRANSFER'))
);
CREATE INDEX ix_job_outbox_pending ON job_outbox (id) WHERE published_at IS NULL;
