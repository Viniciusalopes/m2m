package br.com.vovolinux.m2m.security;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import br.com.vovolinux.m2m.web.ApiException;

/** Acesso ao usuário autenticado. Todo repositório de dados de usuário filtra por {@code id()} (owner_id, D21). */
@Component
public class CurrentUser {

    public record Info(long id, String username) {
    }

    public Optional<Info> optional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof M2mUserDetails u) {
            return Optional.of(new Info(u.id(), u.getUsername()));
        }
        return Optional.empty();
    }

    public Info require() {
        return optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "auth.nao-autenticado"));
    }
}
