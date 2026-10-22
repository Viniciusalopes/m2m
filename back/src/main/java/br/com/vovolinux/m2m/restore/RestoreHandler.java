package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.backup.ArchiveBuilder;
import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.ArchiveValidator;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.BackupNaming;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.backup.RetentionService;
import br.com.vovolinux.m2m.backup.SourceScanner;
import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FsOps;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job RESTORE (D8): restauração em espelho. Valida o backup, faz o backup de segurança do destino (PRE_RESTORE),
 * extrai com escrita segura, exclui os extras e confere o resultado. Falha ou emergência depois do backup de
 * segurança deixam o destino parcial e oferecem desfazer.
 */
@Component
public class RestoreHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RestoreHandler.class);

    private final BackupArchiveRepository archives;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final ArchiveValidator validator;
    private final ManifestLoader loader;
    private final Extractor extractor;
    private final ArchiveBuilder builder;
    private final SourceScanner scanner;
    private final TreeComparator comparator;
    private final RetentionService retention;
    private final RestoreRunRepository runs;
    private final br.com.vovolinux.m2m.profile.ProfileRepository profiles;
    private final JsonMapper json;
    private final NotificationService notifications;
    private final Path tmpRoot;

    public RestoreHandler(BackupArchiveRepository archives, MachineRepository machines, GatewayPool pool,
            ArchiveValidator validator, ManifestLoader loader, Extractor extractor, ArchiveBuilder builder,
            SourceScanner scanner, TreeComparator comparator, RetentionService retention, RestoreRunRepository runs,
            br.com.vovolinux.m2m.profile.ProfileRepository profiles, JsonMapper json, NotificationService notifications,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.archives = archives;
        this.machines = machines;
        this.pool = pool;
        this.validator = validator;
        this.loader = loader;
        this.extractor = extractor;
        this.builder = builder;
        this.scanner = scanner;
        this.comparator = comparator;
        this.retention = retention;
        this.runs = runs;
        this.profiles = profiles;
        this.json = json;
        this.notifications = notifications;
        this.tmpRoot = Path.of(tmpDir);
    }

    @Override
    public String type() {
        return JobType.RESTORE;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        Set<Long> ids = new HashSet<>();
        ids.add(params.get("machineId").asLong());
        archives.find(job.ownerId(), params.get("archiveId").asLong()).map(BackupArchive::storageMachineId)
                .ifPresent(ids::add);
        return ids;
    }

    @Override
    public void recover(Job job) {
        runs.markInterruptedByJob(job.id());
    }

    /** Alvos da restauração: índice do diretório no manifesto → caminho no destino. */
    static Map<Integer, String> targets(JsonNode params) {
        Map<Integer, String> roots = new TreeMap<>();
        for (JsonNode t : params.get("targets")) {
            roots.put(t.get("dir").asInt(), t.get("path").asText());
        }
        return roots;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long archiveId = ctx.params().get("archiveId").asLong();
        BackupArchive archive = archives.find(ctx.ownerId(), archiveId)
                .orElseThrow(() -> new JobFailure("backup.nao-encontrado"));
        Machine dest = machines.find(ctx.ownerId(), ctx.params().get("machineId").asLong())
                .orElseThrow(() -> new JobFailure("perfil.maquina-inexistente"));
        Machine storage = archive.storageMachineId() == null ? null
                : machines.find(ctx.ownerId(), archive.storageMachineId()).orElse(null);
        if (storage == null) {
            throw new JobFailure("backup.arquivo-indisponivel", Map.of("status", "storage-removido"));
        }
        Map<Integer, String> roots = targets(ctx.params());
        boolean undo = ctx.params().path("undo").asBoolean(false);

        long runId = runs.insert(ctx.ownerId(), ctx.id(), archive.id(), archive.fileName(), dest.id(), dest.name(), false,
                json.writeValueAsString(Map.of("targets", roots, "undo", undo)));
        Path tmpDir = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(tmpDir);
        ArchiveBuilder.Session session = new ArchiveBuilder.Session(tmpDir);
        Long[] preId = {null};
        boolean[] changedDestination = {false};
        Map<String, Object> counts = new HashMap<>();
        String status = "FAILED";
        try {
            execute(ctx, archive, dest, storage, roots, undo, session, preId, changedDestination, counts);
            status = "COMPLETED";
            ctx.result().putAll(counts);
            ctx.result().put("restoreRunId", runId);
            if (preId[0] != null) {
                ctx.result().put("preArchiveId", preId[0]);
            }
            if (counts.get("linksSkipped") instanceof Number n && n.longValue() > 0) {
                ctx.completeWithWarnings();
            }
        } catch (CancellationException e) {
            status = "INTERRUPTED";
            throw e;
        } catch (Exception e) {
            status = ctx.token().isCancelled() ? "INTERRUPTED" : "FAILED";
            throw ctx.token().isCancelled() ? new CancellationException("interrompido") : e;
        } finally {
            if (!status.equals("COMPLETED")) {
                ctx.result().put("partial", changedDestination[0]);
                if (preId[0] != null) {
                    ctx.result().put("preArchiveId", preId[0]);
                }
                ctx.result().put("restoreRunId", runId);
            }
            runs.finish(runId, status, json.writeValueAsString(counts), preId[0]);
            cleanup(session);
        }
    }

    private void execute(JobContext ctx, BackupArchive archive, Machine dest, Machine storage,
            Map<Integer, String> roots, boolean undo, ArchiveBuilder.Session session, Long[] preId,
            boolean[] changed, Map<String, Object> counts) throws Exception {
        JobProgress progress = ctx.progress();
        progress.step("CHECK_CONNECTION");
        try (GatewayPool.Lease dstLease = pool.acquire(dest); GatewayPool.Lease stoLease = pool.acquire(storage)) {
            FileGateway dst = dstLease.gateway();
            FileGateway sto = stoLease.gateway();
            ctx.token().throwIfCancelled();
            if (dest.protocol() == Protocol.FTP || dest.protocol() == Protocol.FTPS) {
                ctx.message("WARN", "restauracao.fidelidade-parcial", Map.of("maquina", dest.name()));
            }

            // ---- 1. validação do backup antes de qualquer alteração (D6) ----
            progress.step("VALIDATING");
            progress.totals(archive.archiveBytes(), 0);
            try (InputStream in = new ArchiveBuilder.ProgressInputStream(sto.openRead(ArchiveFiles.path(archive), 0),
                    progress)) {
                validator.validate(in, session.tmpDir, ctx.token());
            } catch (ArchiveValidator.ValidationFailed e) {
                archives.setStatus(archive.id(), ArchiveStatus.CORRUPTED, e.getMessage());
                notifications.notify(ctx.ownerId(), "BACKUP_CORRUPTED", NotificationService.ERROR,
                        Map.of("arquivo", archive.fileName(), "perfil", archive.profileNameSnapshot(), "motivo",
                                String.valueOf(e.getMessage())), "backup", archive.id());
                throw new JobFailure("restauracao.backup-corrompido", Map.of("motivo", String.valueOf(e.getMessage())));
            } catch (NoSuchFileException e) {
                archives.setStatus(archive.id(), ArchiveStatus.MISSING, "arquivo ausente");
                throw new JobFailure("restauracao.backup-ausente", Map.of("arquivo", archive.fileName()));
            }
            ManifestLoader.Loaded manifest = loader.load(sto, archive);
            for (Manifest.Directory d : manifest.header().directories()) {
                if (!roots.containsKey(d.index())) {
                    throw new JobFailure("restauracao.destino-sem-caminho", Map.of("diretorio", d.path()));
                }
            }

            // ---- 2. backup de segurança do destino (PRE_RESTORE) ----
            progress.step("SAFETY_BACKUP");
            progress.resetCounters();
            preId[0] = safetyBackup(ctx, archive, dest, dst, roots, session);

            // ---- 3. extração ----
            changed[0] = true;
            progress.step("COPYING");
            progress.resetCounters();
            progress.totals(archive.totalBytes(), archive.itemCount());
            Extractor.Counts c = extractor.extract(ctx, sto.openRead(ArchiveFiles.path(archive), 0), dst,
                    new Extractor.Options(name -> destinationOf(name, roots), null, true));
            counts.put("created", c.created);
            counts.put("overwritten", c.overwritten);
            counts.put("linksSkipped", c.linksSkipped);
            counts.put("bytes", c.bytes);

            // ---- 4. extras ----
            progress.step("DELETING_EXTRAS");
            long deleted = deleteExtras(ctx, manifest, roots, dst, dest);
            counts.put("deleted", deleted);
            if (undo) {
                for (String created : manifest.header().createdBySync()) {
                    FsOps.deleteTree(dst, created); // diretórios que a restauração desfeita havia criado
                    deleted++;
                }
                counts.put("deleted", deleted);
            }

            // ---- 5. verificação rápida do resultado ----
            progress.step("VERIFYING");
            progress.resetCounters();
            verify(ctx, manifest, roots, dst, dest);
        }
    }

    /** Backup de segurança: itens existentes dos diretórios de destino; falha cancela a restauração (nada alterado). */
    private Long safetyBackup(JobContext ctx, BackupArchive archive, Machine dest, FileGateway dst,
            Map<Integer, String> roots, ArchiveBuilder.Session session) throws Exception {
        try {
            List<String> existing = new ArrayList<>();
            List<String> created = new ArrayList<>();
            for (String root : new java.util.LinkedHashSet<>(roots.values())) {
                Optional<FileEntry> e = dst.lstatIfExists(root);
                if (e.isPresent() && e.get().isDirectory()) {
                    existing.add(root);
                } else {
                    created.add(root);
                }
            }
            // armazenamento: o do perfil (se ainda existir); senão o do próprio backup
            Machine safetyStorage;
            String safetyDir;
            Long profileId = archive.profileId();
            var profile = profileId == null ? Optional.<br.com.vovolinux.m2m.profile.ProfileRepository.Row>empty()
                    : profiles.find(ctx.ownerId(), profileId);
            if (profile.isPresent()) {
                safetyStorage = machines.find(ctx.ownerId(), profile.get().storageMachineId()).orElseThrow();
                safetyDir = profile.get().storageDir();
            } else {
                safetyStorage = machines.find(ctx.ownerId(), archive.storageMachineId()).orElseThrow();
                safetyDir = archive.storageDir();
            }
            try (GatewayPool.Lease lease = pool.acquire(safetyStorage)) {
                FileGateway sto = lease.gateway();
                sto.mkdirs(safetyDir);
                Path scanFile = session.tmpDir.resolve("scan.ndjson");
                session.roots = existing;
                SourceScanner.Summary scan = scanner.scan(dst, existing, scanFile, ctx.token(),
                        ctx.progress()::current);
                ctx.progress().totals(scan.bytes(), scan.items());
                var free = sto.freeSpace(safetyDir);
                if (free.isPresent() && free.getAsLong() < scan.bytes()) {
                    throw new JobFailure("backup.espaco-insuficiente",
                            Map.of("necessario", scan.bytes(), "livre", free.getAsLong()));
                }
                ZonedDateTime now = ZonedDateTime.now(BackupNaming.ZONE);
                String name = builder.uniqueName(sto, safetyDir,
                        t -> BackupNaming.safety(archive.profileNameSnapshot(), "pre-restore", dest.name(), t, false),
                        now);
                List<Manifest.Directory> dirs = new ArrayList<>();
                for (int i = 0; i < existing.size(); i++) {
                    dirs.add(new Manifest.Directory(i + 1, existing.get(i),
                            List.of(new Manifest.MappingRef(dest.id(), dest.name(), existing.get(i)))));
                }
                boolean full = dest.protocol() == Protocol.LOCAL || dest.protocol() == Protocol.SFTP;
                Manifest.Header header = new Manifest.Header(Manifest.FORMAT_VERSION, "PRE_RESTORE",
                        archive.profileNameSnapshot(), dest.name(), dest.protocol().name(), full,
                        full ? List.of() : List.of("permissions-not-preserved", "symlinks-not-preserved"),
                        now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), dirs, created);
                ArchiveBuilder.Built built = builder.build(ctx, session, dst, sto, safetyDir, name, scanFile, header);
                ArchiveValidator.Result v = built.validation();
                long id = archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profileId,
                        archive.profileNameSnapshot(), "PRE_RESTORE", ArchiveStatus.COMPLETED, true,
                        safetyStorage.id(), safetyStorage.name(), safetyDir, name, dest.name(),
                        dest.protocol().name(), full, v.items(), v.files(), v.bytes(), v.archiveBytes(),
                        v.archiveSha256(), false, json.writeValueAsString(dirs),
                        json.writeValueAsString(session.warnings), null, ctx.id(), dest.name()));
                if (profileId != null) {
                    retention.applySafety(profileId, "PRE_RESTORE", dest.name());
                }
                return id;
            }
        } catch (CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (ctx.token().isCancelled()) {
                throw new CancellationException("interrompido");
            }
            String motivo = e instanceof JobFailure f ? f.codigo() : String.valueOf(e.getMessage());
            throw new JobFailure("restauracao.backup-de-seguranca-falhou", Map.of("motivo", motivo));
        }
    }

    static String destinationOf(String entryName, Map<Integer, String> roots) {
        int slash = entryName.indexOf('/');
        String first = slash < 0 ? entryName : entryName.substring(0, slash);
        if (!first.startsWith("dir-")) {
            return null;
        }
        int idx;
        try {
            idx = Integer.parseInt(first.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
        String root = roots.get(idx);
        if (root == null) {
            return null;
        }
        return slash < 0 ? root : root + "/" + entryName.substring(slash + 1);
    }

    /** Exclui, do mais profundo ao mais raso, o que existe no destino e não está no backup (só dentro dos alvos). */
    private long deleteExtras(JobContext ctx, ManifestLoader.Loaded manifest, Map<Integer, String> roots,
            FileGateway dst, Machine dest) throws IOException {
        long deleted = 0;
        for (Manifest.Directory d : manifest.header().directories()) {
            String root = roots.get(d.index());
            List<String> extras = new ArrayList<>();
            comparator.compare(manifest.trees().getOrDefault(d.index(), new br.com.vovolinux.m2m.compare.ManifestTree("vazio", true)),
                    new GatewayTree(dst, root, dest.name()), TreeComparator.Mode.QUICK,
                    TreeComparator.MTIME_TOLERANCE_MILLIS, ctx.token(), new TreeComparator.Visitor() {
                        public void onlySource(String rel, Node n) {
                        }

                        public void onlyTarget(String rel, Node n) {
                            extras.add(rel);
                        }

                        public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> r) {
                        }

                        public void same(String rel, Node s, Node t) {
                        }
                    });
            for (int i = extras.size() - 1; i >= 0; i--) {
                ctx.token().throwIfCancelled();
                String path = root + "/" + extras.get(i);
                ctx.progress().current(path);
                dst.delete(path);
                deleted++;
            }
        }
        return deleted;
    }

    private void verify(JobContext ctx, ManifestLoader.Loaded manifest, Map<Integer, String> roots, FileGateway dst,
            Machine dest) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Manifest.Directory d : manifest.header().directories()) {
            comparator.compare(manifest.trees().getOrDefault(d.index(), new br.com.vovolinux.m2m.compare.ManifestTree("vazio", true)),
                    new GatewayTree(dst, roots.get(d.index()), dest.name()), TreeComparator.Mode.QUICK,
                    TreeComparator.MTIME_TOLERANCE_MILLIS, ctx.token(), new TreeComparator.Visitor() {
                        public void onlySource(String rel, Node n) {
                            add("falta-no-destino " + rel);
                        }

                        public void onlyTarget(String rel, Node n) {
                            add("sobra-no-destino " + rel);
                        }

                        public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> r) {
                            add("diferente " + rel + " " + r);
                        }

                        public void same(String rel, Node s, Node t) {
                            ctx.progress().itemDone();
                        }

                        private void add(String p) {
                            if (problems.size() < 20) {
                                problems.add(p);
                            }
                        }
                    });
        }
        if (!problems.isEmpty()) {
            throw new JobFailure("restauracao.verificacao-falhou", Map.of("diferencas", problems));
        }
    }

    private void cleanup(ArchiveBuilder.Session session) {
        if (session.partialPath != null) {
            log.debug("Arquivo parcial de segurança pendente: {}", session.partialPath);
        }
        deleteDir(session.tmpDir);
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Não foi possível limpar {}: {}", dir, e.getMessage());
        }
    }
}
