package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.backup.ArchiveBuilder;
import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.ArchiveValidator;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.restore.Extractor;
import br.com.vovolinux.m2m.restore.ManifestLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Job SYNC_UNDO (D20): desfaz uma sincronização pelo backup de segurança diferencial. Os itens sobrescritos ou
 * excluídos voltam ao estado anterior (extração com troca segura) e os itens criados pela sincronização são removidos.
 */
@Component
public class SyncUndoHandler implements JobHandler {

    private final BackupArchiveRepository archives;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final ArchiveValidator validator;
    private final ManifestLoader loader;
    private final Extractor extractor;
    private final SyncStateService states;
    private final SyncRunRepository runs;
    private final Path tmpRoot;

    public SyncUndoHandler(BackupArchiveRepository archives, MachineRepository machines, GatewayPool pool,
            ArchiveValidator validator, ManifestLoader loader, Extractor extractor, SyncStateService states,
            SyncRunRepository runs, @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.archives = archives;
        this.machines = machines;
        this.pool = pool;
        this.validator = validator;
        this.loader = loader;
        this.extractor = extractor;
        this.states = states;
        this.runs = runs;
        this.tmpRoot = Path.of(tmpDir);
    }

    @Override
    public String type() {
        return JobType.SYNC_UNDO;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        Set<Long> ids = new HashSet<>();
        archives.find(job.ownerId(), params.get("archiveId").asLong()).ifPresent(a -> {
            if (a.storageMachineId() != null) {
                ids.add(a.storageMachineId());
            }
        });
        if (params.has("machineId")) {
            ids.add(params.get("machineId").asLong());
        }
        return ids;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        BackupArchive a = archives.find(ctx.ownerId(), ctx.params().get("archiveId").asLong())
                .orElseThrow(() -> new JobFailure("backup.nao-encontrado"));
        if (!"PRE_SYNC".equals(a.type()) || !ArchiveStatus.usable(a.status())) {
            throw new JobFailure("sincronizacao.nao-e-backup-de-seguranca");
        }
        Machine storage = machines.find(ctx.ownerId(), a.storageMachineId())
                .orElseThrow(() -> new JobFailure("backup.arquivo-indisponivel"));
        Path tmpDir = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(tmpDir);
        try {
            ctx.progress().step("CHECK_CONNECTION");
            try (GatewayPool.Lease sl = pool.acquire(storage)) {
                FileGateway sto = sl.gateway();
                ctx.progress().step("VALIDATING");
                try (InputStream in = new ArchiveBuilder.ProgressInputStream(sto.openRead(ArchiveFiles.path(a), 0),
                        ctx.progress())) {
                    validator.validate(in, tmpDir, ctx.token());
                } catch (ArchiveValidator.ValidationFailed e) {
                    archives.setStatus(a.id(), ArchiveStatus.CORRUPTED, e.getMessage());
                    throw new JobFailure("restauracao.backup-corrompido", Map.of("motivo", String.valueOf(e.getMessage())));
                } catch (NoSuchFileException e) {
                    archives.setStatus(a.id(), ArchiveStatus.MISSING, "arquivo ausente");
                    throw new JobFailure("restauracao.backup-ausente", Map.of("arquivo", a.fileName()));
                }
                Manifest.Header header = loader.load(sto, a).header();
                Map<Integer, String> roots = new TreeMap<>();
                long destId = -1;
                for (Manifest.Directory d : header.directories()) {
                    roots.put(d.index(), d.path());
                    if (!d.mappings().isEmpty() && d.mappings().get(0).machineId() != null) {
                        destId = d.mappings().get(0).machineId();
                    }
                }
                final long machineId = destId;
                Machine dest = machines.find(ctx.ownerId(), machineId)
                        .orElseThrow(() -> new JobFailure("restauracao.maquina-do-desfazer-inexistente"));
                try (GatewayPool.Lease dl = pool.acquire(dest)) {
                    FileGateway dst = dl.gateway();
                    ctx.progress().step("COPYING");
                    ctx.progress().resetCounters();
                    ctx.progress().totals(a.totalBytes(), a.itemCount());
                    Extractor.Counts c = extractor.extract(ctx, sto.openRead(ArchiveFiles.path(a), 0), dst,
                            new Extractor.Options(name -> destination(name, roots), null, true));
                    ctx.progress().step("DELETING_EXTRAS");
                    List<String> created = new ArrayList<>(header.createdBySync());
                    created.sort(Comparator.comparingInt(String::length).reversed());
                    long removed = 0;
                    for (String path : created) {
                        ctx.token().throwIfCancelled();
                        ctx.progress().current(path);
                        FsOps.deleteTree(dst, path);
                        removed++;
                    }
                    if (a.profileId() != null) {
                        states.save(a.profileId(), dest.id(), dst, new ArrayList<>(roots.values()), ctx.token());
                    }
                    runs.markUndone(a.id());
                    ctx.result().put("restored", c.created + c.overwritten);
                    ctx.result().put("removed", removed);
                }
            }
        } finally {
            if (Files.isDirectory(tmpDir)) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    static String destination(String entryName, Map<Integer, String> roots) {
        int slash = entryName.indexOf('/');
        String first = slash < 0 ? entryName : entryName.substring(0, slash);
        if (!first.startsWith("dir-")) {
            return null;
        }
        String root;
        try {
            root = roots.get(Integer.parseInt(first.substring(4)));
        } catch (NumberFormatException e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        return slash < 0 ? root : root + "/" + entryName.substring(slash + 1);
    }
}
