package br.com.vovolinux.m2m.verify;

import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Job VERIFY (spec mirror-verification): compara os diretórios do perfil na origem com os caminhos mapeados num
 * destino, em modo rápido (tipo, tamanho, data ±2 s, permissões) ou completo (+ SHA-256 e alvo dos links). Somente
 * leitura: nenhum arquivo é alterado.
 */
@Component
public class VerifyHandler implements JobHandler {

    static final int LIST_LIMIT = 1000;

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final TreeComparator comparator;

    public VerifyHandler(ProfileRepository profiles, MachineRepository machines, GatewayPool pool,
            TreeComparator comparator) {
        this.profiles = profiles;
        this.machines = machines;
        this.pool = pool;
        this.comparator = comparator;
    }

    @Override
    public String type() {
        return JobType.VERIFY;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        Set<Long> ids = new HashSet<>();
        profiles.findById(params.get("profileId").asLong()).ifPresent(p -> ids.add(p.sourceMachineId()));
        ids.add(params.get("destMachineId").asLong());
        return ids;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long profileId = ctx.params().get("profileId").asLong();
        long destId = ctx.params().get("destMachineId").asLong();
        TreeComparator.Mode mode = TreeComparator.Mode.valueOf(ctx.params().get("mode").asText());
        var profile = profiles.find(ctx.ownerId(), profileId).orElseThrow(() -> new JobFailure("perfil.nao-encontrado"));
        Machine source = machines.find(ctx.ownerId(), profile.sourceMachineId())
                .orElseThrow(() -> new JobFailure("perfil.origem-inexistente"));
        Machine dest = machines.find(ctx.ownerId(), destId)
                .orElseThrow(() -> new JobFailure("perfil.maquina-inexistente"));

        List<ProfileRepository.DirRow> dirs = profiles.dirs(profileId);
        List<String> semMapeamento = new ArrayList<>();
        for (ProfileRepository.DirRow d : dirs) {
            if (d.mappings().stream().noneMatch(m -> m.machineId() == destId)) {
                semMapeamento.add(d.path());
            }
        }
        if (!semMapeamento.isEmpty()) {
            throw new JobFailure("verificacao.sem-mapeamento", Map.of("diretorios", semMapeamento,
                    "maquina", dest.name()));
        }

        ctx.progress().step("CHECK_CONNECTION");
        try (GatewayPool.Lease srcLease = pool.acquire(source); GatewayPool.Lease dstLease = pool.acquire(dest)) {
            FileGateway src = srcLease.gateway();
            FileGateway dst = dstLease.gateway();
            ctx.progress().step("COMPARING");

            List<Map<String, Object>> dirReports = new ArrayList<>();
            long filesCompared = 0;
            boolean identical = true;
            boolean modesCompared = true;
            boolean linksCompared = true;
            long specials = 0;
            for (ProfileRepository.DirRow d : dirs) {
                String targetPath = d.mappings().stream().filter(m -> m.machineId() == destId).findFirst().orElseThrow()
                        .path();
                Report r = new Report();
                TreeComparator.Stats stats = comparator.compare(new GatewayTree(src, d.path(), source.name()),
                        new GatewayTree(dst, targetPath, dest.name()), mode,
                        TreeComparator.MTIME_TOLERANCE_MILLIS, ctx.token(), new TreeComparator.Visitor() {
                            @Override
                            public void onlySource(String rel, Node n) {
                                r.onlySource(rel);
                                ctx.progress().itemDone();
                                ctx.progress().current(rel);
                            }

                            @Override
                            public void onlyTarget(String rel, Node n) {
                                r.onlyTarget(rel);
                                ctx.progress().itemDone();
                                ctx.progress().current(rel);
                            }

                            @Override
                            public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> reasons) {
                                r.different(rel, reasons);
                                ctx.progress().itemDone();
                                ctx.progress().current(rel);
                            }

                            @Override
                            public void same(String rel, Node s, Node t) {
                                ctx.progress().itemDone();
                                ctx.progress().current(rel);
                            }
                        });
                filesCompared += stats.filesCompared;
                specials += stats.specialsIgnored;
                modesCompared &= stats.modesCompared;
                linksCompared &= stats.linksCompared;
                boolean same = stats.onlySource == 0 && stats.onlyTarget == 0 && stats.different == 0;
                identical &= same;
                Map<String, Object> rep = new LinkedHashMap<>();
                rep.put("source", d.path());
                rep.put("target", targetPath);
                rep.put("identical", same);
                rep.put("onlySourceCount", stats.onlySource);
                rep.put("onlyTargetCount", stats.onlyTarget);
                rep.put("differentCount", stats.different);
                rep.put("filesCompared", stats.filesCompared);
                rep.put("onlySource", r.onlySource);
                rep.put("onlyTarget", r.onlyTarget);
                rep.put("different", r.different);
                rep.put("truncated", r.truncated);
                dirReports.add(rep);
            }

            List<String> notCompared = new ArrayList<>();
            if (!modesCompared) {
                notCompared.add("permissions");
            }
            if (!linksCompared) {
                notCompared.add("symlinks");
            }
            ctx.result().put("mode", mode.name());
            ctx.result().put("identical", identical);
            ctx.result().put("filesCompared", filesCompared);
            ctx.result().put("sourceMachine", source.name());
            ctx.result().put("targetMachine", dest.name());
            ctx.result().put("directories", dirReports);
            ctx.result().put("notCompared", notCompared);
            ctx.result().put("specialsIgnored", specials);
            if (!notCompared.isEmpty()) {
                ctx.message("INFO", "verificacao.nao-comparado", Map.of("itens", notCompared));
            }
        } catch (IOException e) {
            if (ctx.token().isCancelled()) {
                throw new java.util.concurrent.CancellationException("interrompido");
            }
            throw e;
        }
    }

    /** Acumula as listas do relatório, com limite por categoria. */
    private static final class Report {
        final List<String> onlySource = new ArrayList<>();
        final List<String> onlyTarget = new ArrayList<>();
        final List<Map<String, Object>> different = new ArrayList<>();
        boolean truncated;

        void onlySource(String rel) {
            if (onlySource.size() < LIST_LIMIT) {
                onlySource.add(rel);
            } else {
                truncated = true;
            }
        }

        void onlyTarget(String rel) {
            if (onlyTarget.size() < LIST_LIMIT) {
                onlyTarget.add(rel);
            } else {
                truncated = true;
            }
        }

        void different(String rel, Set<TreeComparator.Reason> reasons) {
            if (different.size() < LIST_LIMIT) {
                different.add(Map.of("path", rel, "reasons", reasons.stream().map(Enum::name).sorted().toList()));
            } else {
                truncated = true;
            }
        }
    }

}
