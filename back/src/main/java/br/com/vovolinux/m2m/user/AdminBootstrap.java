package br.com.vovolinux.m2m.user;

import br.com.vovolinux.m2m.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Cria o administrador inicial a partir do ambiente quando o banco ainda não tem usuários. */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        AppProperties.Admin admin = props.admin();
        String username = blankToNull(admin.username());
        String password = blankToNull(admin.password());
        if (password == null) {
            String motivo = "Banco sem usuários e ADMIN_PASSWORD não definida: defina ADMIN_USERNAME, ADMIN_PASSWORD e "
                    + "ADMIN_EMAIL no .env para criar o administrador inicial";
            log.error(motivo);
            throw new IllegalStateException(motivo);
        }
        String nome = username != null ? username : "admin";
        String email = blankToNull(admin.email());
        if (email == null) {
            email = nome + "@localhost";
            log.warn("ADMIN_EMAIL não definido; usando {} (altere em Configurações)", email);
        }
        users.create(nome, email, encoder.encode(password), "ADMIN");
        log.info("Administrador inicial '{}' criado", nome);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
