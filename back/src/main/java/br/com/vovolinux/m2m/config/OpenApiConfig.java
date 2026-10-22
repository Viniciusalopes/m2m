package br.com.vovolinux.m2m.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI m2mOpenApi() {
        return new OpenAPI().info(new Info().title("m2m - Machine To Machine").version("v1")
                .description("API do m2m. Exige sessão autenticada (login em /api/auth/login)."));
    }
}
