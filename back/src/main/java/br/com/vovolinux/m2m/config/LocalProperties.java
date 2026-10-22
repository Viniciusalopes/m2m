package br.com.vovolinux.m2m.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acesso à máquina LOCAL (o host do m2m).
 *
 * @param enabled M2M_LOCAL_ENABLED
 * @param baseDir onde o host está montado no container (padrão {@code /hostfs})
 * @param roots   HOST_ROOTS: raízes do host acessíveis (padrão {@code /home})
 */
@ConfigurationProperties(prefix = "m2m.local")
public record LocalProperties(boolean enabled, String baseDir, List<String> roots) {
}
