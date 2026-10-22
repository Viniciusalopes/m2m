package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.conflict.ConflictAction;
import br.com.vovolinux.m2m.conflict.ConflictPolicy;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job SELECTIVE_RESTORE (D26): extrai itens escolhidos da árvore do backup para um diretório qualquer de qualquer
 * máquina, preservando a estrutura relativa. Não exclui nada e não faz backup de segurança; conflitos seguem a
 * política de conflito (decidida antes de iniciar, com "aplicar a todos").
 */
@Component
public class SelectiveRestoreHandler implements JobHandler {

    private final BackupArchiveRepository archives;
    private final ArchiveFiles files;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final Extractor extractor;
    private final RestoreRunRepository runs;
    private final JsonMapper json;

    public SelectiveRestoreHandler(ArchiveFiles files, BackupArchiveRepository archives, MachineRepository machines, GatewayPool pool,
            Extractor extractor, RestoreRunRepository runs, JsonMapper json) {
        this.archives = archives;
        this.files = files;
        this.machines = machines;
        this.pool = pool;
        this.extractor = extractor;
        this.runs = runs;
        this.json = json;
    }

    @Override
    public String type() {
        return JobType.SELECTIVE_RESTORE;
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

    /** Entrada do tar escolhida? (o próprio item ou algo dentro dele) → caminho relativo ao dir-N, ou null. */
    static String relative(String entryName, List<String> selected) {
        for (String sel : selected) {
            if (entryName.equals(sel) || entryName.startsWith(sel + "/")) {
                int slash = entryName.indexOf('/');
                if (slash < 0) {
                    return ""; // o próprio dir-N escolhido
                }
                String rel = entryName.substring(slash + 1);
                return rel;
            }
        }
        return null;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        BackupArchive archive = archives.find(ctx.ownerId(), ctx.params().get("archiveId").asLong())
                .orElseThrow(() -> new JobFailure("backup.nao-encontrado"));
        Machine dest = machines.find(ctx.ownerId(), ctx.params().get("machineId").asLong())
                .orElseThrow(() -> new JobFailure("perfil.maquina-inexistente"));
        Machine storage = machines.find(ctx.ownerId(), archive.storageMachineId())
                .orElseThrow(() -> new JobFailure("backup.arquivo-indisponivel"));
        String targetDir = ctx.params().get("targetDir").asText();
        List<String> items = new ArrayList<>();
        ctx.params().get("items").forEach(n -> items.add(n.asText()));
        ConflictAction defaultAction = ConflictAction.valueOf(ctx.params().path("defaultAction").asText("SKIP"));
        Map<String, ConflictAction> decisions = new HashMap<>();
        if (ctx.params().has("decisions")) {
            ctx.params().get("decisions").properties()
                    .forEach(e -> decisions.put(e.getKey(), ConflictAction.valueOf(e.getValue().asText())));
        }

        long runId = runs.insert(ctx.ownerId(), ctx.id(), archive.id(), archive.fileName(), dest.id(), dest.name(), true,
                json.writeValueAsString(Map.of("targetDir", targetDir, "items", items)));
        Map<String, Object> counts = new HashMap<>();
        String status = "FAILED";
        try {
            ctx.progress().step("CHECK_CONNECTION");
            try (GatewayPool.Lease dstLease = pool.acquire(dest); GatewayPool.Lease stoLease = pool.acquire(storage)) {
                FileGateway dst = dstLease.gateway();
                FileGateway sto = stoLease.gateway();
                dst.mkdirs(targetDir);
                ctx.progress().step("COPYING");
                ctx.progress().totals(archive.totalBytes(), archive.itemCount());
                Extractor.Options opts = new Extractor.Options(name -> {
                    String rel = relative(name, items);
                    if (rel == null) {
                        return null;
                    }
                    return rel.isEmpty() ? targetDir : targetDir + "/" + rel;
                }, (entry, destPath, incoming, existing) -> {
                    ConflictAction action = decisions.getOrDefault(entry, defaultAction);
                    ConflictPolicy.Decision d = ConflictPolicy.resolve(action, incoming,
                            new ConflictPolicy.Meta(existing.size(), existing.mtime().toEpochMilli()));
                    if (d == ConflictPolicy.Decision.RENAME) {
                        String parent = FsOps.parentOf(destPath);
                        String novo = ConflictPolicy.renamed(FsOps.nameOf(destPath), n -> {
                            try {
                                return dst.lstatIfExists(parent + "/" + n).isPresent();
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
                        return new Extractor.Resolution(d, parent + "/" + novo);
                    }
                    return new Extractor.Resolution(d == ConflictPolicy.Decision.RESUME
                            ? ConflictPolicy.Decision.WRITE : d, destPath);
                }, false);
                Extractor.Counts c = extractor.extract(ctx, files.openArchive(sto, archive), dst, opts);
                counts.put("created", c.created);
                counts.put("overwritten", c.overwritten);
                counts.put("skipped", c.skipped);
                counts.put("linksSkipped", c.linksSkipped);
                counts.put("bytes", c.bytes);
                if (c.created + c.overwritten == 0 && c.skipped == 0) {
                    throw new JobFailure("restauracao.nada-selecionado");
                }
            }
            status = "COMPLETED";
            ctx.result().putAll(counts);
            ctx.result().put("restoreRunId", runId);
            if (((Number) counts.get("skipped")).longValue() > 0 || ((Number) counts.get("linksSkipped")).longValue() > 0) {
                ctx.completeWithWarnings();
            }
        } catch (CancellationException e) {
            status = "INTERRUPTED";
            throw e;
        } catch (Exception e) {
            status = ctx.token().isCancelled() ? "INTERRUPTED" : "FAILED";
            throw ctx.token().isCancelled() ? new CancellationException("interrompido") : e;
        } finally {
            runs.finish(runId, status, json.writeValueAsString(counts), null);
        }
    }
}
