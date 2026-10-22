package br.com.vovolinux.m2m.user;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final UserRepository users;
    private final CurrentUser currentUser;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AccountController(UserRepository users, CurrentUser currentUser, PasswordEncoder encoder,
            AuditService audit) {
        this.users = users;
        this.currentUser = currentUser;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record Me(long id, String username, String email, String role) {
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 200) String newPassword) {
    }

    public record ChangeEmail(@NotBlank @Email @Size(max = 255) String email) {
    }

    @GetMapping
    public Me me() {
        AppUser u = users.findById(currentUser.require().id()).orElseThrow();
        return new Me(u.id(), u.username(), u.email(), u.role());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePassword body) {
        AppUser u = users.findById(currentUser.require().id()).orElseThrow();
        if (!encoder.matches(body.currentPassword(), u.passwordHash())) {
            audit.record("PASSWORD_CHANGE", u.username(), AuditService.FALHA, Map.of("motivo", "senha-atual"));
            throw new ApiException(HttpStatus.BAD_REQUEST, "conta.senha-atual-incorreta");
        }
        users.updatePassword(u.id(), encoder.encode(body.newPassword()));
        audit.record("PASSWORD_CHANGE", u.username(), AuditService.SUCESSO, Map.of());
    }

    @PutMapping("/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeEmail(@Valid @RequestBody ChangeEmail body) {
        AppUser u = users.findById(currentUser.require().id()).orElseThrow();
        users.updateEmail(u.id(), body.email().trim());
        audit.record("EMAIL_CHANGE", u.username(), AuditService.SUCESSO, Map.of("email", body.email().trim()));
    }
}
