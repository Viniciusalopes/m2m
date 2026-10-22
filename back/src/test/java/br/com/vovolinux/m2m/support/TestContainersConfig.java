package br.com.vovolinux.m2m.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    static {
        // "host" falso da máquina LOCAL: /home/<usuário>/ dentro de target/test-hostfs
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-hostfs/home/teste"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18");
    }
}
