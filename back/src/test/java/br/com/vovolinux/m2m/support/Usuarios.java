package br.com.vovolinux.m2m.support;

import br.com.vovolinux.m2m.user.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Cria usuários descartáveis para que os testes não interfiram entre si. */
@Component
public class Usuarios {

    public static final String SENHA = "Senha-Usuario-Teste-1";
    private static final AtomicInteger SEQ = new AtomicInteger();

    public record Criado(long id, String username) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public Usuarios(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public Criado novo() {
        String username = "teste" + SEQ.incrementAndGet() + "_" + System.nanoTime();
        long id = users.create(username, username + "@exemplo.test", encoder.encode(SENHA), "ADMIN");
        return new Criado(id, username);
    }
}
