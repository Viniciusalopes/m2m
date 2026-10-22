package br.com.vovolinux.m2m.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração da instalação, vinda de variáveis de ambiente (.env). */
@ConfigurationProperties(prefix = "m2m")
public record AppProperties(Admin admin, String secretKey) {

    public record Admin(String username, String password, String email) {
    }
}
