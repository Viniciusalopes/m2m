package br.com.vovolinux.m2m.settings;

import br.com.vovolinux.m2m.audit.AuditService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Aviso permanente da chave mestra até o usuário confirmar que a guardou (D32). */
@RestController
@RequestMapping("/api/system/master-key")
public class MasterKeyController {

    static final String CHAVE = "master_key_acknowledged";

    private final SettingsRepository settings;
    private final AuditService audit;

    public MasterKeyController(SettingsRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public record Status(boolean acknowledged) {
    }

    @GetMapping
    public Status status() {
        return new Status(settings.get(CHAVE).map(v -> v.contains("true")).orElse(false));
    }

    @PostMapping("/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge() {
        settings.put(CHAVE, "true");
        audit.record("MASTER_KEY_ACK", "master_key", AuditService.SUCESSO, Map.of());
    }
}
