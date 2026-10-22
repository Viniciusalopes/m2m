-- V5: chaves de criptografia de backups por perfil (M4). O keyset do Tink fica cifrado pela chave mestra.
-- A chave sobrevive à exclusão do perfil: backups que o perfil deixou para trás continuam decifráveis.
-- A tabela da V3 nunca teve uso (sem linhas): é recriada para que a chave sobreviva à exclusão do perfil.
DROP TABLE profile_key;

CREATE TABLE profile_key (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key_id        VARCHAR(40)  NOT NULL UNIQUE,
    owner_id      BIGINT       NOT NULL REFERENCES app_user (id),
    profile_id    BIGINT       REFERENCES profile (id) ON DELETE SET NULL,
    profile_name  VARCHAR(100) NOT NULL,
    keyset_enc    BYTEA        NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_profile_key_active ON profile_key (profile_id) WHERE active AND profile_id IS NOT NULL;

ALTER TABLE backup_archive ADD COLUMN key_id VARCHAR(40);
