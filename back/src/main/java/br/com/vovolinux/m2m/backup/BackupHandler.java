package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobProgress;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.notification.NotificationService;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job BACKUP (D4–D7): varredura → gravação {@code .tar.zst} em streaming no armazenamento (como {@code .partial}) →
 * validação por releitura → renomeação → manifesto lateral → registro, retenção e verificação de suspeito.
 */
@Component
public class BackupHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(BackupHandler.class);

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final SourceScanner scanner;
    private final ArchiveBuilder builder;
    private final BackupArchiveRepository archives;
    private final RetentionService retention;
    private final JsonMapper json;
    private final NotificationService notifications;
    private final br.com.vovolinux.m2m.crypto.ArchiveCrypto crypto;
    private final Path tmpRoot;

    public BackupHandler(ProfileRepository profiles, MachineRepository machines, GatewayPool pool,
            SourceScanner scanner, ArchiveBuilder builder, BackupArchiveRepository archives,
            RetentionService retention, JsonMapper json, NotificationService notifications,
            br.com.vovolinux.m2m.crypto.ArchiveCrypto crypto, @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.profiles = profiles;
        this.machines = machines;
        this.pool = pool;
        this.scanner = scanner;
        this.builder = builder;
        this.archives = archives;
        this.retention = retention;
        this.json = json;
        this.notifications = notifications;
        this.crypto = crypto;
        this.tmpRoot = Path.of(tmpDir);
    }

    @Override
    public String type() {
        return JobType.BACKUP;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        return profiles.findById(params.get("profileId").asLong())
                .map(p -> (Set<Long>) new HashSet<>(List.of(p.sourceMachineId(), p.storageMachineId())))
                .orElse(Set.of());
    }

    /** Estado de uma execução, para a limpeza em qualquer desfecho. */
    private static final class Run {
        final ArchiveBuilder.Session session;
        final Machine storage;
        boolean finalized;

        Run(ArchiveBuilder.Session session, Machine storage) {
            this.session = session;
            this.storage = storage;
        }
    }

    record Result(String status, Map<String, Object> summary) {
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long profileId = ctx.params().get("profileId").asLong();
        String complementOverride = ctx.params().hasNonNull("complement") ? ctx.params().get("complement").asText()
                : null;
        ProfileRepository.Row profile = profiles.find(ctx.ownerId(), profileId)
                .orElseThrow(() -> new JobFailure("perfil.nao-encontrado"));
        Machine source = machines.find(ctx.ownerId(), profile.sourceMachineId())
                .orElseThrow(() -> new JobFailure("perfil.origem-inexistente"));
        Machine storage = machines.find(ctx.ownerId(), profile.storageMachineId())
                .orElseThrow(() -> new JobFailure("perfil.armazenamento-inexistente"));
        List<ProfileRepository.DirRow> dirs = profiles.dirs(profileId);
        if (dirs.isEmpty()) {
            throw new JobFailure("backup.perfil-sem-diretorios");
        }

        Path tmpDir = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(tmpDir);
        Run run = new Run(new ArchiveBuilder.Session(tmpDir), storage);
        String error = null;
        String outcome = ArchiveStatus.FAILED;
        try {
            Result result = execute(ctx, profile, source, storage, dirs, complementOverride, run);
            run.finalized = true;
            outcome = result.status();
            ctx.result().putAll(result.summary());
            if (!run.session.warnings.isEmpty() || ArchiveStatus.SUSPECT.equals(result.status())
                    || ArchiveStatus.COMPLETED_WITH_WARNINGS.equals(result.status())) {
                ctx.completeWithWarnings();
            }
        } catch (CancellationException e) {
            outcome = ArchiveStatus.INTERRUPTED;
            error = "interrompido pelo usuário";
            throw e;
        } catch (Exception e) {
            if (ctx.token().isCancelled()) {
                outcome = ArchiveStatus.INTERRUPTED;
                error = "interrompido pelo usuário";
                throw new CancellationException("interrompido");
            }
            error = e instanceof JobFailure f ? f.codigo() + " " + f.parametros() : e.getMessage();
            throw e;
        } finally {
            cleanup(run);
            if (!run.finalized) {
                recordFailure(ctx, profile, source, storage, run, outcome, error);
            }
        }
    }

    private Result execute(JobContext ctx, ProfileRepository.Row profile, Machine source, Machine storage,
            List<ProfileRepository.DirRow> dirs, String complementOverride, Run run) throws Exception {
        JobProgress progress = ctx.progress();
        String storageDir = profile.storageDir();
        ArchiveBuilder.Session session = run.session;

        progress.step("CHECK_CONNECTION");
        try (GatewayPool.Lease srcLease = pool.acquire(source);
                GatewayPool.Lease dstLease = pool.acquire(storage)) {
            FileGateway src = srcLease.gateway();
            FileGateway dst = dstLease.gateway();
            ctx.token().throwIfCancelled();
            dst.mkdirs(storageDir);

            // ---- varredura prévia (8.1) ----
            progress.step("SCANNING");
            Path scanFile = session.tmpDir.resolve("scan.ndjson");
            List<String> roots = dirs.stream().map(ProfileRepository.DirRow::path).toList();
            session.roots = roots;
            SourceScanner.Summary scan = scanner.scan(src, roots, scanFile, ctx.token(), progress::current);
            progress.current(null);
            progress.totals(scan.bytes(), scan.items());
            for (String special : scan.specials()) {
                builder.warn(ctx, session, "backup.item-especial-ignorado", Map.of("caminho", special));
            }
            checkFreeSpace(dst, storageDir, scan.bytes());

            // ---- gravação, validação e finalização ----
            ZonedDateTime started = ZonedDateTime.now(BackupNaming.ZONE);
            String complement = complementOverride != null && !complementOverride.isBlank() ? complementOverride
                    : profile.complement();
            boolean encrypted = profile.encryptionEnabled();
            if (encrypted) {
                session.key = crypto.forProfile(ctx.ownerId(), profile.id(), profile.name());
            }
            String finalName = builder.uniqueName(dst, storageDir,
                    t -> BackupNaming.regular(profile.name(), complement, source.name(), t, encrypted), started);
            Manifest.Header header = header(profile, source, dirs, started);
            ArchiveBuilder.Built built = builder.build(ctx, session, src, dst, storageDir, finalName, scanFile,
                    header);
            ArchiveValidator.Result validation = built.validation();

            Map<String, Object> summary = new HashMap<>();
            summary.put("fileName", finalName);
            summary.put("items", validation.items());
            summary.put("files", validation.files());
            summary.put("bytes", validation.bytes());
            summary.put("archiveBytes", validation.archiveBytes());
            summary.put("sha256", validation.archiveSha256());

            BackupArchive previous = archives.lastAccepted(profile.id()).orElse(null);
            boolean suspect = isSuspect(previous, validation.files(), validation.bytes(), profile.suspectThreshold());
            String status;
            if (suspect) {
                status = ArchiveStatus.SUSPECT;
            } else if (session.warnings.isEmpty()) {
                status = ArchiveStatus.COMPLETED;
            } else {
                status = ArchiveStatus.COMPLETED_WITH_WARNINGS;
            }
            long id = archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profile.id(),
                    profile.name(), "REGULAR", status, !suspect, storage.id(), storage.name(), storageDir, finalName,
                    source.name(), source.protocol().name(), header.fullFidelity(), validation.items(),
                    validation.files(), validation.bytes(), validation.archiveBytes(), validation.archiveSha256(),
                    encrypted, json.writeValueAsString(header.directories()),
                    json.writeValueAsString(session.warnings), null, ctx.id(), null,
                    session.key == null ? null : session.key.keyId()));
            summary.put("archiveId", id);
            summary.put("encrypted", encrypted);
            summary.put("archiveStatus", status);
            if (suspect) {
                Map<String, Object> sp = Map.of("arquivo", finalName, "arquivos", validation.files(), "anteriores",
                        previous == null ? 0 : previous.fileCount(), "perfil", profile.name());
                ctx.message("WARN", "backup.suspeito", sp);
                notifications.notify(ctx.ownerId(), "BACKUP_SUSPECT", NotificationService.WARN, sp, "backup", id);
            } else {
                progress.step("RETENTION");
                RetentionService.Outcome r = retention.applyRegular(profile.id());
                summary.put("retentionRemoved", r.removed());
                for (Map<String, Object> f : r.failures()) {
                    ctx.message("WARN", "backup.retencao-falhou", f);
                }
            }
            return new Result(status, summary);
        }
    }

    Manifest.Header header(ProfileRepository.Row profile, Machine source, List<ProfileRepository.DirRow> dirs,
            ZonedDateTime when) {
        boolean full = source.protocol() == Protocol.LOCAL || source.protocol() == Protocol.SFTP;
        List<String> fidelityWarnings = full ? List.of()
                : List.of("permissions-not-preserved", "symlinks-not-preserved");
        List<Manifest.Directory> d = new ArrayList<>();
        for (int i = 0; i < dirs.size(); i++) {
            List<Manifest.MappingRef> maps = new ArrayList<>();
            for (ProfileRepository.MappingRow m : dirs.get(i).mappings()) {
                String name = machines.find(profile.ownerId(), m.machineId()).map(Machine::name).orElse("?");
                maps.add(new Manifest.MappingRef(m.machineId(), name, m.path()));
            }
            d.add(new Manifest.Directory(i + 1, dirs.get(i).path(), maps));
        }
        return new Manifest.Header(Manifest.FORMAT_VERSION, "REGULAR", profile.name(), source.name(),
                source.protocol().name(), full, fidelityWarnings,
                when.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), d, List.of());
    }

    private void checkFreeSpace(FileGateway dst, String dir, long needed) throws IOException {
        OptionalLong free = dst.freeSpace(dir);
        if (free.isPresent() && free.getAsLong() < needed) {
            throw new JobFailure("backup.espaco-insuficiente", Map.of("necessario", needed, "livre", free.getAsLong()));
        }
    }

    static boolean isSuspect(BackupArchive previous, long files, long bytes, double threshold) {
        if (previous == null) {
            return false; // o primeiro backup do perfil nunca é suspeito (D25)
        }
        if (previous.fileCount() > 0 && files == 0) {
            return true;
        }
        return files < threshold * previous.fileCount() || bytes < threshold * previous.totalBytes();
    }

    /** Remove o {@code .partial} e os temporários do job, usando uma conexão nova (as antigas podem estar fechadas). */
    private void cleanup(Run run) {
        if (run.session.partialPath != null) {
            try (GatewayPool.Lease lease = pool.acquire(run.storage)) {
                try {
                    lease.gateway().delete(run.session.partialPath);
                } catch (NoSuchFileException ignorado) {
                    // nunca chegou a ser criado
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Não foi possível remover o arquivo parcial {}: {}", run.session.partialPath,
                        e.getMessage());
            }
        }
        deleteTree(run.session.tmpDir);
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Não foi possível limpar {}: {}", dir, e.getMessage());
        }
    }

    private void recordFailure(JobContext ctx, ProfileRepository.Row profile, Machine source, Machine storage,
            Run run, String status, String error) {
        try {
            archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profile.id(), profile.name(),
                    "REGULAR", status, false, storage.id(), storage.name(), profile.storageDir(),
                    run.session.finalName == null ? "(não gerado)" : run.session.finalName, source.name(),
                    source.protocol().name(), true, 0, 0, 0, 0, null, false, "[]",
                    json.writeValueAsString(run.session.warnings), error, ctx.id(), null));
        } catch (RuntimeException e) {
            log.warn("Não foi possível registrar o histórico do backup falho: {}", e.getMessage());
        }
    }

    @Override
    public void recover(Job job) {
        // Jobs interrompidos por queda: remove o .partial gravado no armazenamento, se houver.
        try {
            JsonNode p = json.readTree(job.params());
            var profile = profiles.findById(p.get("profileId").asLong());
            if (profile.isEmpty()) {
                return;
            }
            Machine storage = machines.find(job.ownerId(), profile.get().storageMachineId()).orElse(null);
            if (storage == null) {
                return;
            }
            try (GatewayPool.Lease lease = pool.acquire(storage)) {
                FileGateway gw = lease.gateway();
                for (var e : gw.list(profile.get().storageDir())) {
                    if (e.name().endsWith(".partial")
                            && e.name().startsWith(br.com.vovolinux.m2m.profile.Paths.slug(profile.get().name()) + "_")) {
                        gw.delete(e.path());
                        log.info("Arquivo parcial removido na recuperação: {}", e.path());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Recuperação do backup {}: {}", job.id(), e.getMessage());
        }
    }
}
