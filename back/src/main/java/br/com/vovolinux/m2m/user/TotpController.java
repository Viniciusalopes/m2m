package br.com.vovolinux.m2m.user;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.security.TotpService;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ativação e desativação do segundo fator (D31). */
@RestController
@RequestMapping("/api/me/2fa")
public class TotpController {

    public record Status(boolean enabled, boolean setupPending, int recoveryCodesLeft) {
    }

    public record CodeBody(String code) {
    }

    public record DisableBody(String password, String code) {
    }

    private final TotpService totp;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public TotpController(TotpService totp, UserRepository users, CurrentUser currentUser, PasswordEncoder encoder,
            AuditService audit) {
        this.totp = totp;
        this.users = users;
        this.currentUser = currentUser;
        this.encoder = encoder;
        this.audit = audit;
    }

    private AppUser me() {
        return users.findById(currentUser.require().id()).orElseThrow();
    }

    @GetMapping
    public Status status() {
        long id = currentUser.require().id();
        return new Status(totp.isEnabled(id), totp.setupPending(id), totp.recoveryCodesLeft(id));
    }

    /** Começa a ativação: devolve o segredo e a URI do QR code. Nada vale até o primeiro código ser confirmado. */
    @PostMapping("/setup")
    public TotpService.Setup setup() {
        AppUser u = me();
        return totp.begin(u.id(), u.username())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "segundo-fator.ja-ativo"));
    }

    public record Enabled(List<String> recoveryCodes) {
    }

    /** Confirma um código e ativa; os códigos de recuperação aparecem só desta vez. */
    @PostMapping("/enable")
    public Enabled enable(@RequestBody CodeBody body) {
        AppUser u = me();
        List<String> codes = totp.enable(u.id(), body.code())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "segundo-fator.codigo-invalido"));
        audit.record("TOTP_ENABLE", u.username(), AuditService.SUCESSO, Map.of());
        return new Enabled(codes);
    }

    /** Desativar exige a senha e um código válido: uma sessão esquecida aberta não basta. */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@RequestBody DisableBody body) {
        AppUser u = me();
        if (body.password() == null || !encoder.matches(body.password(), u.passwordHash())) {
            audit.record("TOTP_DISABLE", u.username(), AuditService.FALHA, Map.of("motivo", "senha"));
            throw new ApiException(HttpStatus.BAD_REQUEST, "conta.senha-atual-incorreta");
        }
        if (!totp.verify(u.id(), body.code())) {
            audit.record("TOTP_DISABLE", u.username(), AuditService.FALHA, Map.of("motivo", "codigo"));
            throw new ApiException(HttpStatus.BAD_REQUEST, "segundo-fator.codigo-invalido");
        }
        totp.disable(u.id());
        audit.record("TOTP_DISABLE", u.username(), AuditService.SUCESSO, Map.of());
    }
}
