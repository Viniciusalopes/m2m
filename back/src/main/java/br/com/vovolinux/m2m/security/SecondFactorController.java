package br.com.vovolinux.m2m.security;

import br.com.vovolinux.m2m.user.AppUser;
import br.com.vovolinux.m2m.user.UserRepository;
import br.com.vovolinux.m2m.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Segunda etapa do login: o código TOTP (ou de recuperação) libera a sessão que passou pela senha. */
@RestController
@RequestMapping("/api/auth")
public class SecondFactorController {

    public record Code(String code) {
    }

    private final TotpService totp;
    private final UserRepository users;
    private final LoginAttemptService attempts;

    public SecondFactorController(TotpService totp, UserRepository users, LoginAttemptService attempts) {
        this.totp = totp;
        this.users = users;
        this.attempts = attempts;
    }

    @PostMapping("/2fa")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@RequestBody Code body, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        Object attr = session == null ? null : session.getAttribute(SecondFactorFilter.PENDING);
        if (!(attr instanceof SecondFactorFilter.Pending pending) || pending.expiresAt().isBefore(Instant.now())) {
            if (session != null) {
                session.invalidate();
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.segundo-fator-expirado");
        }
        AppUser user = users.findById(pending.userId()).orElseThrow();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now())) {
            session.invalidate();
            throw new ApiException(HttpStatus.LOCKED, "auth.conta-bloqueada", Map.of("minutos", 5));
        }
        if (!totp.verify(user.id(), body.code())) {
            boolean locked = attempts.secondFactorFailed(user, ip(req));
            if (locked) {
                session.invalidate();
                throw new ApiException(HttpStatus.LOCKED, "auth.conta-bloqueada", Map.of("minutos", 5));
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.codigo-invalido");
        }
        session.removeAttribute(SecondFactorFilter.PENDING);
        req.changeSessionId();
        attempts.secondFactorPassed(user, ip(req));
    }

    private static String ip(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
