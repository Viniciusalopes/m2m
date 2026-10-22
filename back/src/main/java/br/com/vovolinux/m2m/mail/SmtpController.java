package br.com.vovolinux.m2m.mail;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Seção "E-mail (SMTP)" das Configurações: cada usuário cadastra o próprio servidor (D27). */
@RestController
@RequestMapping("/api/me/smtp")
public class SmtpController {

    public record SmtpView(String host, int port, String security, String username, boolean hasPassword,
            String fromAddress, boolean enabled, boolean configured) {
    }

    public record SmtpRequest(
            @NotBlank @Size(max = 255) String host,
            @Min(1) @Max(65535) int port,
            @Pattern(regexp = "NONE|STARTTLS|SSL") String security,
            @Size(max = 255) String username,
            /** vazio mantém a senha salva */
            @Size(max = 500) String password,
            @NotBlank @Size(max = 255) String fromAddress,
            boolean enabled) {
    }

    public record TestResult(boolean ok, String error) {
    }

    private final SmtpSettingRepository repository;
    private final EmailService email;
    private final CryptoService crypto;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final AuditService audit;

    public SmtpController(SmtpSettingRepository repository, EmailService email, CryptoService crypto,
            CurrentUser currentUser, UserRepository users, AuditService audit) {
        this.repository = repository;
        this.email = email;
        this.crypto = crypto;
        this.currentUser = currentUser;
        this.users = users;
        this.audit = audit;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @GetMapping
    public SmtpView get() {
        return repository.find(owner()).map(s -> new SmtpView(s.host(), s.port(), s.security(), s.username(),
                s.passwordEnc() != null, s.fromAddress(), s.enabled(), true))
                .orElse(new SmtpView("", 587, "STARTTLS", "", false, "", false, false));
    }

    @PutMapping
    public SmtpView put(@Valid @RequestBody SmtpRequest req) {
        long owner = owner();
        Optional<SmtpSetting> atual = repository.find(owner);
        byte[] pw = atual.map(SmtpSetting::passwordEnc).orElse(null);
        if (req.password() != null && !req.password().isEmpty()) {
            pw = crypto.encrypt(req.password());
        }
        String user = req.username() == null || req.username().isBlank() ? null : req.username().trim();
        if (user == null) {
            pw = null;
        }
        repository.save(new SmtpSetting(owner, req.host().trim(), req.port(),
                req.security() == null ? "STARTTLS" : req.security(), user, pw, req.fromAddress().trim(),
                req.enabled()));
        // a senha nunca entra na auditoria
        audit.record("SMTP_UPDATE", req.host().trim(), AuditService.SUCESSO,
                Map.of("porta", req.port(), "seguranca", String.valueOf(req.security()), "ativo", req.enabled()));
        return get();
    }

    /** E-mail de teste com os dados do formulário; devolve o erro do servidor quando falha. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.OK)
    public TestResult test(@Valid @RequestBody SmtpRequest req) {
        long owner = owner();
        var user = users.findById(owner).orElseThrow();
        String pw = null;
        if (req.password() != null && !req.password().isEmpty()) {
            pw = req.password();
        } else {
            pw = repository.find(owner).map(SmtpSetting::passwordEnc).map(crypto::decryptString).orElse(null);
        }
        String username = req.username() == null || req.username().isBlank() ? null : req.username().trim();
        if (username == null) {
            pw = null;
        }
        EmailService.Effective e = new EmailService.Effective(req.host().trim(), req.port(),
                req.security() == null ? "STARTTLS" : req.security(), username, pw, req.fromAddress().trim());
        try {
            email.sendNow(e, user.email(), "[m2m] E-mail de teste",
                    "Este é um e-mail de teste do m2m - Machine To Machine. Se você o recebeu, a configuração de "
                            + "e-mail (SMTP) está correta.");
            audit.record("SMTP_TEST", req.host().trim(), AuditService.SUCESSO, Map.of());
            return new TestResult(true, null);
        } catch (Exception ex) {
            audit.record("SMTP_TEST", req.host().trim(), AuditService.FALHA, Map.of());
            return new TestResult(false, EmailService.rootMessage(ex));
        }
    }

}
