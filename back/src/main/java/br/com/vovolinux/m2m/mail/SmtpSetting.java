package br.com.vovolinux.m2m.mail;

/** Configuração SMTP de um usuário (a senha só existe cifrada no banco). */
public record SmtpSetting(
        long ownerId,
        String host,
        int port,
        String security,
        String username,
        byte[] passwordEnc,
        String fromAddress,
        boolean enabled) {
}
