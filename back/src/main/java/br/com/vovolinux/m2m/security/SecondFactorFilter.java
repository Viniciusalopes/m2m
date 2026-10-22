package br.com.vovolinux.m2m.security;

import br.com.vovolinux.m2m.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Barreira do login em duas etapas (D31): depois da senha, a sessão guarda a pendência do segundo fator e NADA além
 * de informar o código, sair ou pedir o token CSRF é atendido até ele ser aceito. A pendência vale 5 minutos.
 */
public class SecondFactorFilter extends OncePerRequestFilter {

    public static final String PENDING = "M2M_2FA_PENDING";
    public static final long VALID_SECONDS = 300;
    private static final Set<String> ALLOWED = Set.of("/api/auth/2fa", "/api/auth/logout", "/api/auth/csrf");

    /** Quem passou pela senha e quando a pendência vence. */
    public record Pending(long userId, Instant expiresAt) {
    }

    private final JsonMapper json;

    public SecondFactorFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PENDING);
        if (pending == null || ALLOWED.contains(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        boolean expired = pending instanceof Pending p && p.expiresAt().isBefore(Instant.now());
        if (expired) {
            session.invalidate();
        }
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(),
                new ApiError(expired ? "auth.segundo-fator-expirado" : "auth.segundo-fator-pendente", Map.of()));
    }
}
