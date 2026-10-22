package br.com.vovolinux.m2m.crypto;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import br.com.vovolinux.m2m.profile.Paths;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exportação da chave de criptografia de um perfil (D29): auditada, porque quem a tem decifra os backups. */
@RestController
@RequestMapping("/api/profiles")
public class ProfileKeyController {

    private final ProfileRepository profiles;
    private final ArchiveCrypto crypto;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public ProfileKeyController(ProfileRepository profiles, ArchiveCrypto crypto, CurrentUser currentUser,
            AuditService audit) {
        this.profiles = profiles;
        this.crypto = crypto;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping("/{id}/encryption-key")
    public ResponseEntity<String> export(@PathVariable long id) {
        long owner = currentUser.require().id();
        ProfileRepository.Row p = profiles.find(owner, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "perfil.nao-encontrado"));
        if (!p.encryptionEnabled() && !crypto.profileHasKey(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "criptografia.sem-chave");
        }
        // perfil com criptografia ligada e ainda sem backup: a chave nasce agora
        crypto.forProfile(owner, id, p.name());
        String file = crypto.exportKey(owner, id)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "criptografia.sem-chave"));
        audit.record("PROFILE_KEY_EXPORT", p.name(), AuditService.SUCESSO, Map.of("perfil", p.name()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("chave-" + Paths.slug(p.name()) + ".json").build().toString())
                .body(file);
    }
}
