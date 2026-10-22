-- V6: último passo de tempo (30 s) aceito do TOTP: um código já usado não vale de novo (RFC 6238, seção 5.2)
ALTER TABLE user_totp ADD COLUMN last_step BIGINT;
