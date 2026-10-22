package br.com.vovolinux.m2m.backup;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retenção das duas últimas versões (D7): ao aceitar um backup, exclui os aceitos e não fixados além dos 2 mais
 * novos do perfil. Backups de segurança têm retenção própria (2 por perfil, máquina de destino e tipo).
 */
@Service
public class RetentionService {

    public static final int KEEP = 2;
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final BackupArchiveRepository repository;
    private final ArchiveFiles files;

    public RetentionService(BackupArchiveRepository repository, ArchiveFiles files) {
        this.repository = repository;
        this.files = files;
    }

    /** Resultado da limpeza: o que foi removido e o que não pôde ser. */
    public record Outcome(int removed, List<Map<String, Object>> failures) {
    }

    public Outcome applyRegular(long profileId) {
        return prune(repository.acceptedUnpinned(profileId, "REGULAR"));
    }

    public Outcome applySafety(long profileId, String type, String destMachineName) {
        return prune(repository.safetyCandidates(profileId, type, destMachineName));
    }

    private Outcome prune(List<BackupArchive> newestFirst) {
        int removed = 0;
        java.util.ArrayList<Map<String, Object>> failures = new java.util.ArrayList<>();
        for (int i = KEEP; i < newestFirst.size(); i++) {
            BackupArchive a = newestFirst.get(i);
            try {
                files.delete(a);
                repository.delete(a.id());
                removed++;
            } catch (IOException | RuntimeException e) {
                // Mantém o registro marcado com erro e segue: o backup novo não é afetado.
                log.warn("Retenção: não foi possível excluir {}: {}", a.fileName(), e.getMessage());
                repository.setStatus(a.id(), a.status(), "retencao: " + e.getMessage());
                failures.add(Map.of("arquivo", a.fileName(), "detalhe", String.valueOf(e.getMessage())));
            }
        }
        return new Outcome(removed, failures);
    }
}
