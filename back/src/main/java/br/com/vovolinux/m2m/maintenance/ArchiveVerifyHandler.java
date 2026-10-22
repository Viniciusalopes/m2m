package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.ArchiveValidator;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.notification.NotificationService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Job ARCHIVE_VERIFY (D30): relê cada backup não ausente com a validação da D6. Falha de integridade →
 * {@code CORRUPTED}; arquivo inexistente → {@code MISSING}; ambos notificam.
 */
@Component
public class ArchiveVerifyHandler implements JobHandler {

    private final BackupArchiveRepository archives;
    private final ArchiveFiles files;
    private final GatewayPool pool;
    private final ArchiveValidator validator;
    private final NotificationService notifications;
    private final Path tmpRoot;

    public ArchiveVerifyHandler(BackupArchiveRepository archives, ArchiveFiles files, GatewayPool pool,
            ArchiveValidator validator, NotificationService notifications,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.archives = archives;
        this.files = files;
        this.pool = pool;
        this.validator = validator;
        this.notifications = notifications;
        this.tmpRoot = Path.of(tmpDir);
    }

    @Override
    public String type() {
        return JobType.ARCHIVE_VERIFY;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        List<BackupArchive> todos = archives.verifiable(ctx.ownerId());
        ctx.progress().step("VERIFYING");
        ctx.progress().totals(todos.stream().mapToLong(BackupArchive::archiveBytes).sum(), todos.size());
        Path tmp = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(tmp);
        int ok = 0;
        int corrompidos = 0;
        int ausentes = 0;
        try {
            for (BackupArchive a : todos) {
                ctx.token().throwIfCancelled();
                ctx.progress().current(a.fileName());
                Outcome r;
                try {
                    r = verificar(ctx, a, tmp);
                } catch (IOException e) {
                    // um armazenamento inalcançável não pode derrubar a verificação dos demais backups
                    ctx.message("WARN", "verificacao-periodica.armazenamento-inacessivel",
                            Map.of("arquivo", a.fileName(), "detalhe", String.valueOf(e.getMessage())));
                    r = Outcome.SKIPPED;
                }
                switch (r) {
                    case OK -> ok++;
                    case CORRUPTED -> corrompidos++;
                    case MISSING -> ausentes++;
                    case SKIPPED -> ctx.progress().warning();
                }
                ctx.progress().itemDone();
            }
        } finally {
            deleteTree(tmp);
        }
        ctx.result().put("verified", todos.size());
        ctx.result().put("ok", ok);
        ctx.result().put("corrupted", corrompidos);
        ctx.result().put("missing", ausentes);
        if (corrompidos + ausentes > 0) {
            ctx.completeWithWarnings();
        }
    }

    private enum Outcome { OK, CORRUPTED, MISSING, SKIPPED }

    private Outcome verificar(JobContext ctx, BackupArchive a, Path tmp) throws IOException {
        Machine storage = files.storageOf(a).orElse(null);
        if (storage == null) {
            ctx.message("WARN", "verificacao-periodica.armazenamento-removido", Map.of("arquivo", a.fileName()));
            return Outcome.SKIPPED;
        }
        try (GatewayPool.Lease lease = pool.acquire(storage)) {
            FileGateway gw = lease.gateway();
            String path = ArchiveFiles.path(a);
            if (gw.lstatIfExists(path).isEmpty()) {
                marcar(ctx, a, ArchiveStatus.MISSING, "arquivo inexistente", "BACKUP_MISSING");
                return Outcome.MISSING;
            }
            try (InputStream in = files.openArchive(gw, a)) {
                ArchiveValidator.Result r = validator.validate(in, tmp, ctx.token());
                if (a.sha256() != null && !a.sha256().equals(r.archiveSha256())) {
                    marcar(ctx, a, ArchiveStatus.CORRUPTED, "SHA-256 do arquivo divergente", "BACKUP_CORRUPTED");
                    return Outcome.CORRUPTED;
                }
            } catch (ArchiveValidator.ValidationFailed e) {
                marcar(ctx, a, ArchiveStatus.CORRUPTED, String.valueOf(e.getMessage()), "BACKUP_CORRUPTED");
                return Outcome.CORRUPTED;
            } catch (NoSuchFileException e) {
                marcar(ctx, a, ArchiveStatus.MISSING, "arquivo inexistente", "BACKUP_MISSING");
                return Outcome.MISSING;
            } catch (br.com.vovolinux.m2m.files.GatewayException e) {
                if (!"backup.chave-ausente".equals(e.codigo())) {
                    lease.discard();
                    throw e;
                }
                // chave de criptografia ausente: não dá para verificar, mas o arquivo não é culpado
                ctx.message("WARN", "verificacao-periodica.chave-ausente", Map.of("arquivo", a.fileName()));
                return Outcome.SKIPPED;
            } catch (IOException e) {
                lease.discard();
                throw e;
            }
        }
        if (ArchiveStatus.CORRUPTED.equals(a.status())) {
            archives.setStatus(a.id(), a.accepted() ? ArchiveStatus.COMPLETED : ArchiveStatus.SUSPECT, null);
        }
        return Outcome.OK;
    }

    private void marcar(JobContext ctx, BackupArchive a, String status, String motivo, String notification) {
        if (!status.equals(a.status())) {
            archives.setStatus(a.id(), status, motivo);
            notifications.notify(a.ownerId(), notification, NotificationService.ERROR,
                    Map.of("arquivo", a.fileName(), "perfil", String.valueOf(a.profileNameSnapshot()), "motivo", motivo),
                    "backup", a.id());
        }
        ctx.message("ERROR", "verificacao-periodica." + (status.equals(ArchiveStatus.MISSING) ? "ausente" : "corrompido"),
                Map.of("arquivo", a.fileName(), "motivo", motivo));
    }

    static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignorado) {
            // limpeza best-effort
        }
    }
}
