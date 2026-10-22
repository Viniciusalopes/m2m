package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.ManifestTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.conflict.ConflictAction;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayFactory;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Fidelity;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.profile.Paths;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Regras de negócio da restauração: caminhos padrão, prévia, confirmação, desfazer e restauração seletiva. */
@Service
public class RestoreService {

    public record Target(int dir, String path) {
    }

    public record RestoreRequest(Long machineId, List<Target> targets, Boolean confirmed) {
    }

    public record DefaultTarget(int dir, String sourcePath, String targetPath) {
    }

    public record DirPreview(int dir, String sourcePath, String targetPath, boolean targetExists, long create,
            long overwrite, long delete, long files) {
    }

    public record Preview(List<DirPreview> directories, long create, long overwrite, long delete,
            List<String> warnings, String archiveFile, String destMachine) {
    }

    public record SelectiveRequest(Long machineId, String targetDir, List<String> items, String defaultAction,
            Map<String, String> decisions) {
    }

    public record Conflict(String entry, String targetPath, long incomingSize, long incomingMtime, long existingSize,
            long existingMtime, boolean typeConflict) {
    }

    private final BackupArchiveRepository archives;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final GatewayFactory factory;
    private final ManifestLoader loader;
    private final TreeComparator comparator;
    private final JobService jobService;
    private final AuditService audit;
    private final LocalProperties local;

    public RestoreService(BackupArchiveRepository archives, MachineRepository machines, GatewayPool pool,
            GatewayFactory factory, ManifestLoader loader, TreeComparator comparator, JobService jobService,
            AuditService audit, LocalProperties local) {
        this.archives = archives;
        this.machines = machines;
        this.pool = pool;
        this.factory = factory;
        this.loader = loader;
        this.comparator = comparator;
        this.jobService = jobService;
        this.audit = audit;
        this.local = local;
    }

    private BackupArchive usable(long ownerId, long id) {
        BackupArchive a = archives.find(ownerId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "backup.nao-encontrado"));
        if (!ArchiveStatus.usable(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel", Map.of("status", a.status()));
        }
        return a;
    }

    private Machine writable(long ownerId, long machineId) {
        Machine m = machines.find(ownerId, machineId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
        if (!factory.capabilitiesOf(m).writable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.destino-somente-leitura",
                    Map.of("maquina", m.name()));
        }
        return m;
    }

    private ManifestLoader.Loaded manifest(BackupArchive a) {
        Machine storage = machines.find(a.ownerId(), a.storageMachineId() == null ? -1 : a.storageMachineId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel"));
        try (GatewayPool.Lease lease = pool.acquire(storage)) {
            try {
                return loader.load(lease.gateway(), a);
            } catch (IOException e) {
                lease.discard();
                throw e;
            }
        } catch (GatewayException e) {
            throw new ApiException(e.codigo().startsWith("identidade.") ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    e.codigo(), e.parametros());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "backup.manifesto-indisponivel",
                    Map.of("detalhe", String.valueOf(e.getMessage())));
        }
    }

    /** Caminhos de destino pré-preenchidos: o mapeamento gravado no manifesto (ou o original, se o destino é a origem). */
    public List<DefaultTarget> defaults(long ownerId, long archiveId, long machineId) {
        BackupArchive a = usable(ownerId, archiveId);
        Machine m = machines.find(ownerId, machineId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
        Manifest.Header header = manifest(a).header();
        List<DefaultTarget> out = new ArrayList<>();
        for (Manifest.Directory d : header.directories()) {
            String target = null;
            if (m.name().equals(header.sourceMachine()) && !a.type().startsWith("PRE_")) {
                target = d.path(); // o destino é a própria origem do backup
            } else {
                for (Manifest.MappingRef r : d.mappings()) {
                    if ((r.machineId() != null && r.machineId() == m.id()) || m.name().equals(r.machine())) {
                        target = r.path();
                    }
                }
            }
            out.add(new DefaultTarget(d.index(), d.path(), target));
        }
        return out;
    }

    private Map<Integer, String> validateTargets(Machine m, Manifest.Header header, List<Target> targets) {
        Map<Integer, String> roots = new LinkedHashMap<>();
        if (targets == null) {
            targets = List.of();
        }
        for (Target t : targets) {
            String p;
            try {
                p = Paths.normalize(t.path());
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.caminho-invalido", Map.of("caminho", String.valueOf(t.path())));
            }
            boolean raiz = p.equals("/") || (m.protocol() == Protocol.LOCAL
                    && local.roots().stream().map(Paths::normalize).anyMatch(p::equals));
            if (raiz) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorio-raiz", Map.of("diretorio", p));
            }
            roots.put(t.dir(), p);
        }
        for (Manifest.Directory d : header.directories()) {
            if (!roots.containsKey(d.index())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.destino-sem-caminho",
                        Map.of("diretorio", d.path()));
            }
        }
        List<String> vistos = new ArrayList<>();
        for (String p : roots.values()) {
            for (String v : vistos) {
                if (Paths.overlap(v, p)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.mapeamento-sobreposto",
                            Map.of("caminho", p, "conflito", v));
                }
            }
            vistos.add(p);
        }
        return roots;
    }

    /** Prévia: quantos itens serão criados, sobrescritos e excluídos em cada diretório de destino. */
    public Preview preview(long ownerId, long archiveId, RestoreRequest req) {
        BackupArchive a = usable(ownerId, archiveId);
        Machine m = writable(ownerId, req.machineId());
        ManifestLoader.Loaded manifest = manifest(a);
        Map<Integer, String> roots = validateTargets(m, manifest.header(), req.targets());
        List<DirPreview> dirs = new ArrayList<>();
        long create = 0;
        long overwrite = 0;
        long delete = 0;
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            FileGateway gw = lease.gateway();
            for (Manifest.Directory d : manifest.header().directories()) {
                String root = roots.get(d.index());
                boolean exists = gw.lstatIfExists(root).map(FileEntry::isDirectory).orElse(false);
                long[] c = new long[4]; // criar, sobrescrever, excluir, arquivos
                ManifestTree tree = manifest.trees().getOrDefault(d.index(), new ManifestTree("vazio", true));
                comparator.compare(tree, new GatewayTree(gw, root, m.name()), TreeComparator.Mode.QUICK,
                        TreeComparator.MTIME_TOLERANCE_MILLIS, new CancellationToken(),
                        new TreeComparator.Visitor() {
                            public void onlySource(String rel, Node n) {
                                c[0]++;
                                if (n.type() == FileType.FILE) {
                                    c[3]++;
                                }
                            }

                            public void onlyTarget(String rel, Node n) {
                                c[2]++;
                            }

                            public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> r) {
                                if (s.type() == FileType.FILE && t.type() == FileType.FILE) {
                                    c[1]++;
                                    c[3]++;
                                } else {
                                    c[0]++;
                                    c[2]++;
                                }
                            }

                            public void same(String rel, Node s, Node t) {
                                if (s.type() == FileType.FILE) {
                                    c[1]++; // arquivos existentes também são substituídos integralmente
                                    c[3]++;
                                }
                            }
                        });
                dirs.add(new DirPreview(d.index(), d.path(), root, exists, c[0], c[1], c[2], c[3]));
                create += c[0];
                overwrite += c[1];
                delete += c[2];
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        List<String> warnings = new ArrayList<>();
        Fidelity f = Fidelity.of(m.protocol(), factory.capabilitiesOf(m));
        if (!f.permissions() || !f.symlinks()) {
            warnings.add("destino-fidelidade-parcial");
        }
        return new Preview(dirs, create, overwrite, delete, warnings, a.fileName(), m.name());
    }

    private static ApiException gateway(IOException e) {
        if (e instanceof GatewayException g) {
            return new ApiException(g.codigo().startsWith("identidade.") ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    g.codigo(), g.parametros());
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "conexao.falha", Map.of("detalhe", String.valueOf(e.getMessage())));
    }

    /** Inicia a restauração em espelho; exige confirmação explícita. */
    public Job start(long ownerId, long archiveId, RestoreRequest req, boolean undo) {
        if (!Boolean.TRUE.equals(req.confirmed())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.confirmacao-obrigatoria");
        }
        BackupArchive a = usable(ownerId, archiveId);
        Machine m = writable(ownerId, req.machineId());
        ManifestLoader.Loaded manifest = manifest(a);
        Map<Integer, String> roots = validateTargets(m, manifest.header(), req.targets());
        List<Map<String, Object>> targets = new ArrayList<>();
        roots.forEach((dir, path) -> targets.add(Map.of("dir", dir, "path", path)));
        Map<String, Object> params = new HashMap<>();
        params.put("archiveId", archiveId);
        params.put("machineId", m.id());
        params.put("targets", targets);
        params.put("undo", undo);
        Job job = jobService.enqueue(ownerId, JobType.RESTORE, "MANUAL", a.profileId(), a.profileNameSnapshot(), params);
        audit.record(undo ? "RESTORE_UNDO" : "RESTORE_START", a.fileName(), AuditService.SUCESSO,
                Map.of("destino", m.name(), "caminhos", roots, "jobId", job.id()));
        return job;
    }

    /** Desfazer: restaura o backup de segurança nas mesmas máquina e caminhos de onde ele foi gerado. */
    public Job undo(long ownerId, long archiveId) {
        BackupArchive a = usable(ownerId, archiveId);
        if (!"PRE_RESTORE".equals(a.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.nao-e-backup-de-seguranca");
        }
        Manifest.Header header = manifest(a).header();
        long machineId = -1;
        List<Target> targets = new ArrayList<>();
        for (Manifest.Directory d : header.directories()) {
            targets.add(new Target(d.index(), d.path()));
            if (!d.mappings().isEmpty() && d.mappings().get(0).machineId() != null) {
                machineId = d.mappings().get(0).machineId();
            }
        }
        if (machineId < 0) {
            Optional<Machine> byName = machines.findAll(ownerId).stream()
                    .filter(m -> m.name().equals(a.destMachineName())).findFirst();
            machineId = byName.map(Machine::id).orElseThrow(
                    () -> new ApiException(HttpStatus.CONFLICT, "restauracao.maquina-do-desfazer-inexistente"));
        }
        return start(ownerId, archiveId, new RestoreRequest(machineId, targets, true), true);
    }

    // ---- restauração seletiva ----

    private List<String> normalizeItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.nada-selecionado");
        }
        List<String> out = new ArrayList<>();
        for (String i : items) {
            String n = i.replaceAll("^/+|/+$", "");
            if (!n.startsWith("dir-") || n.contains("..")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "backup.caminho-invalido", Map.of("caminho", i));
            }
            out.add(n);
        }
        return out;
    }

    /** Itens que já existem no destino e precisam de decisão (política de conflito). */
    public List<Conflict> plan(long ownerId, long archiveId, SelectiveRequest req) {
        BackupArchive a = usable(ownerId, archiveId);
        Machine m = writable(ownerId, req.machineId());
        List<String> items = normalizeItems(req.items());
        String targetDir = normalizeTarget(req.targetDir());
        ManifestLoader.Loaded manifest = manifest(a);
        List<Conflict> conflicts = new ArrayList<>();
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            FileGateway gw = lease.gateway();
            for (var e : manifest.trees().entrySet()) {
                int dir = e.getKey();
                collectConflicts(gw, e.getValue(), "dir-" + dir, "", items, targetDir, conflicts);
                if (conflicts.size() > 5000) {
                    break;
                }
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        return conflicts;
    }

    private void collectConflicts(FileGateway gw, ManifestTree tree, String prefix, String rel, List<String> items,
            String targetDir, List<Conflict> out) throws IOException {
        for (Node n : tree.children(rel)) {
            String childRel = rel.isEmpty() ? n.name() : rel + "/" + n.name();
            String entry = prefix + "/" + childRel;
            boolean selected = SelectiveRestoreHandler.relative(entry, items) != null;
            if (selected && n.type() != FileType.DIRECTORY) {
                String dest = targetDir + "/" + childRel;
                Optional<FileEntry> ex = gw.lstatIfExists(dest);
                if (ex.isPresent()) {
                    out.add(new Conflict(entry, dest, n.size(), n.mtimeMillis(), ex.get().size(),
                            ex.get().mtime().toEpochMilli(), ex.get().isDirectory()));
                }
            }
            if (n.type() == FileType.DIRECTORY && (selected || items.stream().anyMatch(s -> s.startsWith(entry + "/")))) {
                collectConflicts(gw, tree, prefix, childRel, items, targetDir, out);
            }
        }
    }

    private String normalizeTarget(String dir) {
        try {
            String p = Paths.normalize(dir);
            if (p.equals("/")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorio-raiz", Map.of("diretorio", p));
            }
            return p;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.caminho-invalido", Map.of("caminho", String.valueOf(dir)));
        }
    }

    public Job startSelective(long ownerId, long archiveId, SelectiveRequest req) {
        BackupArchive a = usable(ownerId, archiveId);
        Machine m = writable(ownerId, req.machineId());
        List<String> items = normalizeItems(req.items());
        String targetDir = normalizeTarget(req.targetDir());
        ConflictAction defaultAction;
        try {
            defaultAction = req.defaultAction() == null ? ConflictAction.SKIP : ConflictAction.valueOf(req.defaultAction());
            if (req.decisions() != null) {
                req.decisions().values().forEach(ConflictAction::valueOf);
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.acao-de-conflito-invalida");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("archiveId", archiveId);
        params.put("machineId", m.id());
        params.put("targetDir", targetDir);
        params.put("items", items);
        params.put("defaultAction", defaultAction.name());
        params.put("decisions", req.decisions() == null ? Map.of() : req.decisions());
        Job job = jobService.enqueue(ownerId, JobType.SELECTIVE_RESTORE, "MANUAL", a.profileId(),
                a.profileNameSnapshot(), params);
        audit.record("SELECTIVE_RESTORE_START", a.fileName(), AuditService.SUCESSO,
                Map.of("destino", m.name(), "diretorio", targetDir, "itens", items.size(), "jobId", job.id()));
        return job;
    }
}
