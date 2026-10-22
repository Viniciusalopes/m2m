package br.com.vovolinux.m2m.security;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.user.AppUser;
import br.com.vovolinux.m2m.user.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contagem de falhas, bloqueio (5 falhas → 5 min) e registro de tentativas e auditoria de login. */
@Service
public class LoginAttemptService {

    public static final int LIMITE_FALHAS = 5;
    public static final long BLOQUEIO_SEGUNDOS = 300;

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public LoginAttemptService(UserRepository users, JdbcClient jdbc, AuditService audit) {
        this.users = users;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        String username = auth.getName();
        String ip = ip(auth);
        Optional<AppUser> user = users.findByUsername(username);
        String motivo = event.getException() instanceof LockedException ? "BLOQUEADO" : "CREDENCIAIS";
        if (user.isPresent() && !(event.getException() instanceof LockedException)) {
            users.registerFailure(user.get().id(), LIMITE_FALHAS, BLOQUEIO_SEGUNDOS);
        }
        registrar(username, ip, false, motivo);
        audit.record(user.map(AppUser::id).orElse(null), username, ip, "LOGIN", username, AuditService.FALHA,
                Map.of("motivo", motivo));
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        if (!(auth.getPrincipal() instanceof M2mUserDetails u)) {
            return;
        }
        users.resetAttempts(u.id());
        registrar(u.getUsername(), ip(auth), true, null);
        audit.record(u.id(), u.getUsername(), ip(auth), "LOGIN", u.getUsername(), AuditService.SUCESSO, Map.of());
    }

    private void registrar(String username, String ip, boolean ok, String motivo) {
        jdbc.sql("insert into login_attempt (username, ip, success, reason) values (:u, :ip, :ok, :m)")
                .param("u", username).param("ip", ip).param("ok", ok).param("m", motivo).update();
    }

    private static String ip(Authentication auth) {
        return auth.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
    }
}
