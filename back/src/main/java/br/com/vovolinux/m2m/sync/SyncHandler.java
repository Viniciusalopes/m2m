package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.backup.ArchiveBuilder;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.ArchiveValidator;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.BackupNaming;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.backup.RetentionService;
import br.com.vovolinux.m2m.backup.SourceFileReader;
import br.com.vovolinux.m2m.backup.Spool;
import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobProgress;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.notification.NotificationService;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job SYNC (D20, D24): espelha os diretórios do perfil nos caminhos mapeados de um destino, copiando só o que mudou.
 * Ordem: plano → proteções → backup de segurança diferencial (PRE_SYNC) → cópia → exclusões → verificação → estado.
 */
@Component
public class SyncHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncHandler.class);

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final SyncPlanner planner;
    private final SyncStateService states;
    private final SyncRunRepository runs;
    private final ArchiveBuilder builder;
    private final BackupArchiveRepository archives;
    private final RetentionService retention;
    private final SourceFileReader reader;
    private final TreeComparator comparator;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final Path tmpRoot;
    private final int spoolLimit;

    public SyncHandler(ProfileRepository profiles, MachineRepository machines, GatewayPool pool, SyncPlanner planner,
            SyncStateService states, SyncRunRepository runs, ArchiveBuilder builder,
            BackupArchiveRepository archives, RetentionService retention, SourceFileReader reader,
            TreeComparator comparator, NotificationService notifications, JsonMapper json,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir,
            @Value("${m2m.backup.spool-limit:" + Spool.DEFAULT_LIMIT + "}") int spoolLimit) {
        this.profiles = profiles;
        this.machines = machines;
        this.pool = pool;
        this.planner = planner;
        this.states = states;
        this.runs = runs;
        this.builder = builder;
        this.archives = archives;
        this.retention = retention;
        this.reader = reader;
        this.comparator = comparator;
        this.notifications = notifications;
        this.json = json;
        this.tmpRoot = Path.of(tmpDir);
        this.spoolLimit = spoolLimit;
    }

    @Override
    public String type() {
        return JobType.SYNC;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        Set<Long> ids = new HashSet<>();
        profiles.findById(params.get("profileId").asLong())
                .ifPresent(p -> ids.addAll(List.of(p.sourceMachineId(), p.storageMachineId())));
        ids.add(params.get("destMachineId").asLong());
        return ids;
    }

    @Override
    public void recover(Job job) {
        runs.markInterruptedByJob(job.id());
    }

    private static final class State {
        Long preId;
        boolean changed;
        final Map<String, Object> counts = new HashMap<>();
        SyncPlan plan;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long profileId = ctx.params().get("profileId").asLong();
        long destId = ctx.params().get("destMachineId").asLong();
        var profile = profiles.find(ctx.ownerId(), profileId).orElseThrow(() -> new JobFailure("perfil.nao-encontrado"));
        Machine source = machines.find(ctx.ownerId(), profile.sourceMachineId())
                .orElseThrow(() -> new JobFailure("perfil.origem-inexistente"));
        Machine dest = machines.find(ctx.ownerId(), destId)
                .orElseThrow(() -> new JobFailure("perfil.maquina-inexistente"));
        Machine storage = machines.find(ctx.ownerId(), profile.storageMachineId())
                .orElseThrow(() -> new JobFailure("perfil.armazenamento-inexistente"));
        boolean scheduled = "SCHEDULED".equals(ctx.job().trigger());

        List<SyncPlanner.DirPair> pairs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (ProfileRepository.DirRow d : profiles.dirs(profileId)) {
            Optional<ProfileRepository.MappingRow> m = d.mappings().stream().filter(x -> x.machineId() == destId)
                    .findFirst();
            if (m.isEmpty()) {
                missing.add(d.path());
            } else {
                pairs.add(new SyncPlanner.DirPair(d.position(), d.path(), m.get().path()));
            }
        }
        if (!missing.isEmpty()) {
            throw new JobFailure("sincronizacao.destino-sem-mapeamento", Map.of("diretorios", missing));
        }

        long runId = runs.insert(ctx.ownerId(), ctx.id(), profileId, profile.name(), dest.id(), dest.name(),
                ctx.job().trigger());
        Path tmpDir = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(tmpDir);
        ArchiveBuilder.Session session = new ArchiveBuilder.Session(tmpDir);
        State st = new State();
        String status = "FAILED";
        try {
            status = execute(ctx, profile, source, dest, storage, pairs, scheduled, session, st);
            ctx.result().putAll(st.counts);
            ctx.result().put("syncRunId", runId);
            if (st.preId != null) {
                ctx.result().put("preArchiveId", st.preId);
            }
        } catch (CancellationException e) {
            status = "INTERRUPTED";
            throw e;
        } catch (JobFailure e) {
            status = ctx.token().isCancelled() ? "INTERRUPTED" : "FAILED";
            if (scheduled && e.codigo().startsWith("sincronizacao.") && !e.codigo().equals("sincronizacao.verificacao-falhou")) {
                notifications.notify(ctx.ownerId(), "SYNC_ABORTED", NotificationService.WARN,
                        Map.of("perfil", profile.name(), "destino", dest.name(), "motivo",
                                e.codigo() + " " + e.parametros()), "job", ctx.id());
            }
            throw ctx.token().isCancelled() ? new CancellationException("interrompido") : e;
        } catch (Exception e) {
            status = ctx.token().isCancelled() ? "INTERRUPTED" : "FAILED";
            throw ctx.token().isCancelled() ? new CancellationException("interrompido") : e;
        } finally {
            if (!status.equals("COMPLETED") && !status.equals("COMPLETED_WITH_WARNINGS")
                    && !status.equals(JobStatus.AWAITING_CONFIRMATION)) {
                ctx.result().put("partial", st.changed);
                if (st.preId != null) {
                    ctx.result().put("preArchiveId", st.preId);
                }
                ctx.result().put("syncRunId", runId);
            }
            runs.finish(runId, status, planJson(st.plan), json.writeValueAsString(st.counts), st.preId);
            BackupHandlerCleanup.deleteTree(tmpDir);
        }
    }

    private String planJson(SyncPlan p) {
        if (p == null) {
            return "{}";
        }
        return json.writeValueAsString(Map.of("create", p.create(), "update", p.update(), "replace", p.replace(),
                "delete", p.delete(), "directories", p.directories()));
    }

    private String execute(JobContext ctx, ProfileRepository.Row profile, Machine source, Machine dest,
            Machine storage, List<SyncPlanner.DirPair> pairs, boolean scheduled, ArchiveBuilder.Session session,
            State st) throws Exception {
        JobProgress progress = ctx.progress();
        progress.step("CHECK_CONNECTION");
        try (GatewayPool.Lease sl = pool.acquire(source); GatewayPool.Lease dl = pool.acquire(dest);
                GatewayPool.Lease tl = pool.acquire(storage)) {
            FileGateway src = sl.gateway();
            FileGateway dst = dl.gateway();
            FileGateway sto = tl.gateway();
            List<String> destRoots = pairs.stream().map(SyncPlanner.DirPair::targetPath).toList();
            boolean content = ctx.params().path("compareContent").asBoolean(false);

            // ---- 1. plano ----
            progress.step("COMPARING");
            Path planFile = session.tmpDir.resolve("plan.ndjson");
            SyncPlan plan = planner.plan(src, dst, source.name(), dest.name(), pairs, content, planFile, ctx.token());
            st.plan = plan;
            st.counts.put("planCreate", plan.create());
            st.counts.put("planUpdate", plan.update() + plan.replace());
            st.counts.put("planDelete", plan.delete());
            if (plan.specialsIgnored() > 0) {
                ctx.message("WARN", "sincronizacao.itens-especiais-ignorados", Map.of("total", plan.specialsIgnored()));
            }

            // ---- 2. proteções (D24) ----
            progress.step("PROTECTIONS");
            checkProtections(ctx, profile, dest, dst, destRoots, plan, scheduled, st);
            if (st.counts.containsKey("awaiting")) {
                ctx.finishAs(JobStatus.AWAITING_CONFIRMATION);
                return JobStatus.AWAITING_CONFIRMATION;
            }

            // ---- 3. nada a fazer ----
            if (plan.empty()) {
                st.counts.put("alreadySynced", true);
                states.save(profile.id(), dest.id(), dst, destRoots, ctx.token());
                return "COMPLETED";
            }

            // ---- 4. backup de segurança diferencial ----
            progress.step("SAFETY_BACKUP");
            progress.resetCounters();
            st.preId = safetyBackup(ctx, profile, dest, dst, sto, storage, pairs, plan, session);

            // ---- 5. cópia ----
            st.changed = true;
            progress.step("COPYING");
            progress.resetCounters();
            long[] totals = totals(plan);
            progress.totals(totals[1], totals[0]);
            long[] done = copy(ctx, src, dst, pairs, plan, session, st);

            // ---- 6. exclusões ----
            progress.step("DELETING_EXTRAS");
            long deleted = deleteExtras(ctx, dst, pairs, plan);
            st.counts.put("deleted", deleted);

            // ---- 7. verificação rápida ----
            progress.step("VERIFYING");
            progress.resetCounters();
            verify(ctx, src, dst, source, dest, pairs);

            // ---- 8. estado do destino ----
            states.save(profile.id(), dest.id(), dst, destRoots, ctx.token());
            if (!session.warnings.isEmpty() || done[2] > 0 || plan.specialsIgnored() > 0) {
                ctx.completeWithWarnings();
                return "COMPLETED_WITH_WARNINGS";
            }
            return "COMPLETED";
        }
    }

    private void checkProtections(JobContext ctx, ProfileRepository.Row profile, Machine dest, FileGateway dst,
            List<String> destRoots, SyncPlan plan, boolean scheduled, State st) throws Exception {
        // origem vazia × destino com itens
        long emptyWithDest = 0;
        for (SyncPlan.DirSummary d : plan.directories()) {
            if (d.sourceItems() == 0 && d.targetItems() > 0) {
                emptyWithDest += d.targetItems();
            }
        }
        if (emptyWithDest > 0) {
            if (scheduled) {
                throw new JobFailure("sincronizacao.origem-vazia", Map.of("itens", emptyWithDest));
            }
            if (!ctx.params().path("confirmEmptySource").asBoolean(false)) {
                throw new JobFailure("sincronizacao.origem-vazia-nao-confirmada", Map.of("itens", emptyWithDest));
            }
        }
        // trabalho feito no destino
        Optional<SyncStateService.Changes> changes = states.changesSince(profile.id(), dest.id(), dst, destRoots,
                ctx.token());
        if (changes.isEmpty()) {
            if (scheduled) {
                throw new JobFailure("sincronizacao.sem-estado-previo");
            }
        } else if (changes.get().any()) {
            st.counts.put("destinationChanges", changes.get().total());
            if (scheduled) {
                throw new JobFailure("sincronizacao.trabalho-no-destino", Map.of("itens", changes.get().total(),
                        "amostra", changes.get().sample(20)));
            }
            if (!ctx.params().path("confirmDestinationChanges").asBoolean(false)) {
                throw new JobFailure("sincronizacao.trabalho-no-destino-nao-confirmado",
                        Map.of("itens", changes.get().total(), "amostra", changes.get().sample(20)));
            }
        }
        // limite de exclusões (só agendada, e só se ainda não confirmada)
        if (scheduled && !ctx.params().path("confirmedLimit").asBoolean(false)) {
            long deletions = plan.delete() + plan.replace();
            long destItems = plan.directories().stream().mapToLong(SyncPlan.DirSummary::targetItems).sum();
            int pct = ctx.params().path("maxDeletionsPercent").asInt(20);
            int abs = ctx.params().path("maxDeletionsAbs").asInt(500);
            long limit = Math.min(destItems * pct / 100, abs);
            if (deletions > limit) {
                st.counts.put("awaiting", true);
                st.counts.put("deletions", deletions);
                st.counts.put("limit", limit);
                ctx.result().put("reason", "LIMITE_EXCLUSOES");
                ctx.result().put("deletions", deletions);
                ctx.result().put("limit", limit);
                notifications.notify(ctx.ownerId(), "SYNC_AWAITING", NotificationService.WARN,
                        Map.of("perfil", profile.name(), "destino", dest.name(), "motivo",
                                deletions + " exclusões (limite " + limit + ")"), "job", ctx.id());
            }
        }
    }

    private static long[] totals(SyncPlan plan) throws IOException {
        long items = plan.create() + plan.update() + plan.replace();
        return new long[] {items, plan.sourceBytes()};
    }

    // ---- backup de segurança diferencial ----

    private Long safetyBackup(JobContext ctx, ProfileRepository.Row profile, Machine dest, FileGateway dst,
            FileGateway sto, Machine storage, List<SyncPlanner.DirPair> pairs, SyncPlan plan,
            ArchiveBuilder.Session session) throws Exception {
        try {
            Path scanFile = session.tmpDir.resolve("scan.ndjson");
            List<String> created = new ArrayList<>();
            long bytes = 0;
            try (BufferedReader r = Files.newBufferedReader(plan.file(), StandardCharsets.UTF_8);
                    BufferedWriter w = Files.newBufferedWriter(scanFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    PlanItem it = json.readValue(line, PlanItem.class);
                    String root = pairs.get(it.dir() - 1).targetPath();
                    boolean destructive = it.op().equals(PlanItem.UPDATE) || it.op().equals(PlanItem.REPLACE)
                            || it.op().equals(PlanItem.DELETE);
                    if (it.op().equals(PlanItem.CREATE)) {
                        created.add(root + "/" + it.path());
                    }
                    if (destructive && it.tt() != null) {
                        w.write(json.writeValueAsString(new Manifest.Item(it.dir(), it.path(), it.tt(), it.ts(), it.tm(),
                                it.tx(), it.tl(), null)));
                        w.write('\n');
                        if (it.tt().equals("FILE")) {
                            bytes += it.ts();
                        }
                    }
                }
            }
            session.roots = pairs.stream().map(SyncPlanner.DirPair::targetPath).toList();
            sto.mkdirs(profile.storageDir());
            var free = sto.freeSpace(profile.storageDir());
            if (free.isPresent() && free.getAsLong() < bytes) {
                throw new JobFailure("backup.espaco-insuficiente", Map.of("necessario", bytes, "livre", free.getAsLong()));
            }
            ZonedDateTime now = ZonedDateTime.now(BackupNaming.ZONE);
            String name = builder.uniqueName(sto, profile.storageDir(),
                    t -> BackupNaming.safety(profile.name(), "pre-sync", dest.name(), t, false), now);
            List<Manifest.Directory> dirs = new ArrayList<>();
            for (SyncPlanner.DirPair p : pairs) {
                dirs.add(new Manifest.Directory(p.index(), p.targetPath(),
                        List.of(new Manifest.MappingRef(dest.id(), dest.name(), p.targetPath()))));
            }
            boolean full = dest.protocol() == Protocol.LOCAL || dest.protocol() == Protocol.SFTP;
            Manifest.Header header = new Manifest.Header(Manifest.FORMAT_VERSION, "PRE_SYNC", profile.name(),
                    dest.name(), dest.protocol().name(), full,
                    full ? List.of() : List.of("permissions-not-preserved", "symlinks-not-preserved"),
                    now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), dirs, created);
            ArchiveBuilder.Built built = builder.build(ctx, session, dst, sto, profile.storageDir(), name, scanFile,
                    header);
            ArchiveValidator.Result v = built.validation();
            long id = archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profile.id(), profile.name(),
                    "PRE_SYNC", ArchiveStatus.COMPLETED, true, storage.id(), storage.name(), profile.storageDir(), name,
                    dest.name(), dest.protocol().name(), full, v.items(), v.files(), v.bytes(), v.archiveBytes(),
                    v.archiveSha256(), false, json.writeValueAsString(dirs), json.writeValueAsString(session.warnings),
                    null, ctx.id(), dest.name()));
            retention.applySafety(profile.id(), "PRE_SYNC", dest.name());
            session.warnings.clear();
            return id;
        } catch (CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (ctx.token().isCancelled()) {
                throw new CancellationException("interrompido");
            }
            String motivo = e instanceof JobFailure f ? f.codigo() : String.valueOf(e.getMessage());
            throw new JobFailure("sincronizacao.backup-de-seguranca-falhou", Map.of("motivo", motivo));
        }
    }

    // ---- cópia ----

    private record DirMeta(String path, Integer mode, long mtime) {
    }

    /** @return {criados, atualizados, links ignorados} */
    private long[] copy(JobContext ctx, FileGateway src, FileGateway dst, List<SyncPlanner.DirPair> pairs,
            SyncPlan plan, ArchiveBuilder.Session session, State st) throws IOException {
        Capabilities caps = dst.capabilities();
        long[] c = new long[3];
        long bytes = 0;
        List<DirMeta> dirs = new ArrayList<>();
        Set<String> knownDirs = new HashSet<>();
        for (SyncPlanner.DirPair p : pairs) {
            dst.mkdirs(p.targetPath());
            knownDirs.add(p.targetPath());
        }
        try (BufferedReader r = Files.newBufferedReader(plan.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                ctx.token().throwIfCancelled();
                PlanItem it = json.readValue(line, PlanItem.class);
                if (it.op().equals(PlanItem.DELETE)) {
                    continue;
                }
                SyncPlanner.DirPair p = pairs.get(it.dir() - 1);
                String from = p.sourcePath() + "/" + it.path();
                String to = p.targetPath() + "/" + it.path();
                ctx.progress().current(to);
                boolean replace = it.op().equals(PlanItem.REPLACE);
                switch (it.st()) {
                    case "DIRECTORY" -> {
                        if (replace) {
                            FsOps.deleteTree(dst, to);
                        }
                        if (dst.lstatIfExists(to).isEmpty()) {
                            dst.mkdirs(to);
                            c[0]++;
                        }
                        knownDirs.add(to);
                        dirs.add(new DirMeta(to, it.sx(), it.sm()));
                    }
                    case "SPECIAL" -> {
                        // pipes, sockets e dispositivos nunca são copiados
                    }
                    case "SYMLINK" -> {
                        if (!caps.preservesLinks()) {
                            c[2]++;
                            ctx.message("WARN", "restauracao.link-nao-restaurado", Map.of("caminho", to, "alvo",
                                    String.valueOf(it.sl())));
                            break;
                        }
                        ensureParent(dst, to, knownDirs);
                        FsOps.deleteTree(dst, to);
                        dst.symlink(to, it.sl());
                        c[replace || it.op().equals(PlanItem.UPDATE) ? 1 : 0]++;
                    }
                    default -> {
                        SourceFileReader.Result res = reader.read(src, from, session.tmpDir, spoolLimit, ctx.token(),
                                ctx.progress()::addBytes);
                        if (res.skipped()) {
                            builder.warn(ctx, session, "backup.arquivo-removido-durante-a-leitura",
                                    Map.of("caminho", from));
                            break;
                        }
                        try (Spool content = res.content()) {
                            if (res.changed()) {
                                builder.warn(ctx, session, "backup.arquivo-alterado-durante-a-leitura",
                                        Map.of("caminho", from));
                            }
                            ensureParent(dst, to, knownDirs);
                            writeFile(dst, to, content, res, it, caps, replace);
                            bytes += res.size();
                            c[it.op().equals(PlanItem.CREATE) ? 0 : 1]++;
                        }
                    }
                }
                ctx.progress().itemDone();
            }
        }
        for (int i = dirs.size() - 1; i >= 0; i--) {
            DirMeta d = dirs.get(i);
            if (caps.preservesMode() && d.mode() != null) {
                dst.setMode(d.path(), d.mode());
            }
            if (caps.setsMtime()) {
                dst.setMtime(d.path(), Instant.ofEpochMilli(d.mtime()));
            }
        }
        st.counts.put("created", c[0]);
        st.counts.put("updated", c[1]);
        st.counts.put("linksSkipped", c[2]);
        st.counts.put("bytes", bytes);
        return c;
    }

    private void ensureParent(FileGateway dst, String path, Set<String> known) throws IOException {
        String parent = FsOps.parentOf(path);
        if (known.add(parent)) {
            dst.mkdirs(parent);
        }
    }

    private void writeFile(FileGateway dst, String to, Spool content, SourceFileReader.Result res, PlanItem it,
            Capabilities caps, boolean replace) throws IOException {
        String parent = FsOps.parentOf(to);
        String tmp = parent + "/." + FsOps.nameOf(to) + ".m2m-tmp";
        boolean done = false;
        try {
            try (OutputStream out = dst.openWrite(tmp, 0)) {
                content.writeTo(out);
            }
            Integer mode = res.mode() != null ? res.mode() : it.sx();
            if (caps.preservesMode() && mode != null) {
                dst.setMode(tmp, mode);
            }
            if (caps.setsMtime()) {
                dst.setMtime(tmp, Instant.ofEpochMilli(res.mtimeMillis()));
            }
            if (replace) {
                FsOps.deleteTree(dst, to);
            }
            dst.rename(tmp, to);
            done = true;
        } finally {
            if (!done) {
                try {
                    dst.delete(tmp);
                } catch (NoSuchFileException ignorado) {
                    // nunca chegou a existir
                } catch (IOException ignorado) {
                    // conexão possivelmente fechada pela emergência
                }
            }
        }
    }

    // ---- exclusões ----

    private long deleteExtras(JobContext ctx, FileGateway dst, List<SyncPlanner.DirPair> pairs, SyncPlan plan)
            throws IOException {
        List<String[]> deletes = new ArrayList<>(); // {dirIndex, path}
        List<String> replaced = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(plan.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                PlanItem it = json.readValue(line, PlanItem.class);
                if (it.op().equals(PlanItem.REPLACE)) {
                    replaced.add(it.dir() + ":" + it.path());
                } else if (it.op().equals(PlanItem.DELETE)) {
                    deletes.add(new String[] {String.valueOf(it.dir()), it.path()});
                }
            }
        }
        long deleted = 0;
        for (int i = deletes.size() - 1; i >= 0; i--) {
            ctx.token().throwIfCancelled();
            String[] d = deletes.get(i);
            // itens dentro de um diretório substituído já foram apagados junto com ele
            if (replaced.stream().anyMatch(x -> (d[0] + ":" + d[1]).startsWith(x + "/"))) {
                continue;
            }
            String path = pairs.get(Integer.parseInt(d[0]) - 1).targetPath() + "/" + d[1];
            ctx.progress().current(path);
            try {
                dst.delete(path);
                deleted++;
            } catch (NoSuchFileException ignorado) {
                // já removido junto com o pai
            }
        }
        return deleted;
    }

    private void verify(JobContext ctx, FileGateway src, FileGateway dst, Machine source, Machine dest,
            List<SyncPlanner.DirPair> pairs) throws IOException {
        List<String> problems = new ArrayList<>();
        for (SyncPlanner.DirPair p : pairs) {
            comparator.compare(new GatewayTree(src, p.sourcePath(), source.name()),
                    new GatewayTree(dst, p.targetPath(), dest.name()), TreeComparator.Mode.QUICK,
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

                        private void add(String x) {
                            if (problems.size() < 20) {
                                problems.add(x);
                            }
                        }
                    });
        }
        if (!problems.isEmpty()) {
            throw new JobFailure("sincronizacao.verificacao-falhou", Map.of("diferencas", problems));
        }
    }

    /** Reaproveita a limpeza recursiva de diretório temporário. */
    static final class BackupHandlerCleanup {
        static void deleteTree(Path dir) {
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

}
