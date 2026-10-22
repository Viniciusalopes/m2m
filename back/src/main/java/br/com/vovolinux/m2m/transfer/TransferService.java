package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.compare.TreeSource;
import br.com.vovolinux.m2m.conflict.ConflictAction;
import br.com.vovolinux.m2m.conflict.ConflictPolicy;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayFactory;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.OutboxPublisher;
import br.com.vovolinux.m2m.job.ProgressBus;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.Paths;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Regras da transferência manual: expansão, conflitos, fila, operações de arquivo, listagem, filtros, comparação. */
@Service
public class TransferService {

    // ---- pedidos e respostas ----

    public record PlanRequest(Long srcMachineId, List<String> srcPaths, Long dstMachineId, String dstDir,
            Boolean applyFilters, TransferFilter.Definition filter, String conflictDefault,
            Map<String, String> decisions) {
    }

    public record Conflict(String srcPath, String dstPath, long srcSize, long srcMtime, long dstSize, long dstMtime) {
    }

    public record PlanResult(long items, long files, long bytes, long skipped, long ignoredByFilter,
            List<Conflict> conflicts, List<String> warnings, String batchId) {
    }

    public record ListRequest(Long machineId, String path, TransferFilter.Definition filter, Long filterSetId) {
    }

    public record Entry(String name, String path, String type, long size, Instant mtime, Integer mode,
            String linkTarget) {
    }

    public record Listing(String path, String parent, List<Entry> items, int total, int hidden) {
    }

    public record Side(Long machineId, String path, TransferFilter.Definition filter) {
    }

    public record CompareRequest(Side left, Side right, String mode, Integer toleranceMinutes) {
    }

    public record CompareEntry(String name, String leftState, String rightState) {
    }

    public record CompareResult(List<CompareEntry> entries, long different, long identical) {
    }

    /** Um item já resolvido do plano, pronto para virar linha da fila. */
    private record Planned(String kind, String srcPath, String dstPath, long size, long mtime, Integer mode,
            String link, String status, String skipReason, boolean appendFinal, String action) {
    }

    private final TransferRepository repository;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final GatewayFactory factory;
    private final TransferGuard guard;
    private final RunningTransfers running;
    private final OutboxPublisher outbox;
    private final ProgressBus bus;
    private final AuditService audit;
    private final TreeComparator comparator;
    private final JsonMapper json;

    public TransferService(TransferRepository repository, MachineRepository machines, GatewayPool pool,
            GatewayFactory factory, TransferGuard guard, RunningTransfers running, OutboxPublisher outbox,
            ProgressBus bus, AuditService audit, TreeComparator comparator, JsonMapper json) {
        this.repository = repository;
        this.machines = machines;
        this.pool = pool;
        this.factory = factory;
        this.guard = guard;
        this.running = running;
        this.outbox = outbox;
        this.bus = bus;
        this.audit = audit;
        this.comparator = comparator;
        this.json = json;
    }

    // ---- utilitários ----

    private Machine machine(long ownerId, Long id) {
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente");
        }
        return machines.find(ownerId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
    }

    private Machine writable(long ownerId, Long id) {
        Machine m = machine(ownerId, id);
        if (!factory.capabilitiesOf(m).writable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.destino-somente-leitura",
                    Map.of("maquina", m.name()));
        }
        return m;
    }

    private static ApiException gateway(IOException e) {
        if (e instanceof GatewayException g) {
            return new ApiException(g.codigo().startsWith("identidade.") ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    g.codigo(), g.parametros());
        }
        if (e instanceof NoSuchFileException) {
            return new ApiException(HttpStatus.NOT_FOUND, "transferencia.caminho-inexistente",
                    Map.of("caminho", String.valueOf(e.getMessage())));
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "conexao.falha", Map.of("detalhe", String.valueOf(e.getMessage())));
    }

    private static String normalize(String path) {
        try {
            return Paths.normalize(path);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.caminho-invalido",
                    Map.of("caminho", String.valueOf(path)));
        }
    }

    private static TransferFilter.Subject subject(FileEntry e) {
        return new TransferFilter.Subject(e.name(), e.path(), e.type(), e.size(), e.mtime().toEpochMilli(), e.mode());
    }

    private TransferFilter filterOf(long ownerId, TransferFilter.Definition def, Long filterSetId) {
        if (def != null) {
            return TransferFilter.compile(def);
        }
        if (filterSetId != null) {
            var set = repository.filterSet(ownerId, filterSetId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "filtro.nao-encontrado"));
            return TransferFilter.compile(json.readValue(set.definition(), TransferFilter.Definition.class));
        }
        return TransferFilter.NONE;
    }

    // ---- listagem ----

    public Listing list(long ownerId, ListRequest req) {
        Machine m = machine(ownerId, req.machineId());
        TransferFilter filter = filterOf(ownerId, req.filter(), req.filterSetId());
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            FileGateway gw = lease.gateway();
            try {
                String dir = req.path() == null || req.path().isBlank() ? gw.homeDir() : normalize(req.path());
                List<FileEntry> all = gw.list(dir);
                List<Entry> shown = new ArrayList<>();
                int hidden = 0;
                for (FileEntry e : all) {
                    if (filter.hides(subject(e))) {
                        hidden++;
                    } else {
                        shown.add(new Entry(e.name(), e.path(), e.type().name(), e.size(), e.mtime(), e.mode(),
                                e.linkTarget()));
                    }
                }
                shown.sort(Comparator.comparing((Entry e) -> !"DIRECTORY".equals(e.type()))
                        .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
                String parent = dir.equals("/") ? null : FsOps.parentOf(dir);
                return new Listing(dir, parent, shown, all.size(), hidden);
            } catch (NoSuchFileException e) {
                throw new ApiException(HttpStatus.NOT_FOUND, "transferencia.diretorio-inexistente",
                        Map.of("caminho", String.valueOf(req.path())));
            } catch (IOException e) {
                lease.discard();
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
    }

    // ---- plano e início ----

    private static final class Ctx {
        final FileGateway src;
        final FileGateway dst;
        final long ownerId;
        final TransferFilter filter;
        final boolean applyFilters;
        final ConflictAction defaultAction;
        final Map<String, String> decisions;
        final boolean linksSupported;
        final Map<String, Map<String, FileEntry>> dstListings = new HashMap<>();
        final List<Planned> planned = new ArrayList<>();
        final List<Conflict> conflicts = new ArrayList<>();
        final Set<String> taken = new HashSet<>();
        long ignored;

        Ctx(FileGateway src, FileGateway dst, long ownerId, TransferFilter filter, boolean applyFilters,
                ConflictAction defaultAction, Map<String, String> decisions) {
            this.src = src;
            this.dst = dst;
            this.ownerId = ownerId;
            this.filter = filter;
            this.applyFilters = applyFilters;
            this.defaultAction = defaultAction;
            this.decisions = decisions;
            this.linksSupported = dst.capabilities().preservesLinks();
        }

        Map<String, FileEntry> listing(String dir) throws IOException {
            Map<String, FileEntry> cached = dstListings.get(dir);
            if (cached == null) {
                cached = new HashMap<>();
                try {
                    for (FileEntry e : dst.list(dir)) {
                        cached.put(e.name(), e);
                    }
                } catch (NoSuchFileException ignorado) {
                    // diretório novo: nada pode conflitar
                }
                dstListings.put(dir, cached);
            }
            return cached;
        }
    }

    private ConflictAction action(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ConflictAction.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.acao-invalida", Map.of("acao", name));
        }
    }

    private PlanResult expand(long ownerId, PlanRequest req, boolean persist) {
        if (req.srcPaths() == null || req.srcPaths().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.nada-selecionado");
        }
        Machine srcM = machine(ownerId, req.srcMachineId());
        Machine dstM = writable(ownerId, req.dstMachineId());
        if (req.dstDir() == null || req.dstDir().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.caminho-invalido", Map.of("caminho", ""));
        }
        String dstDir = normalize(req.dstDir());
        guard.check(dstM.id(), dstDir);
        TransferFilter filter = filterOf(ownerId, req.filter(), null);
        boolean applyFilters = Boolean.TRUE.equals(req.applyFilters()) && !filter.isEmpty();
        ConflictAction def = action(req.conflictDefault());
        Map<String, String> decisions = req.decisions() == null ? Map.of() : req.decisions();
        try (GatewayPool.Lease sl = pool.acquire(srcM); GatewayPool.Lease dl = pool.acquire(dstM)) {
            Ctx c = new Ctx(sl.gateway(), dl.gateway(), ownerId, filter, applyFilters, def, decisions);
            try {
                for (String raw : req.srcPaths()) {
                    String p = normalize(raw);
                    FileEntry e = c.src.lstat(p);
                    String dstPath = (dstDir.equals("/") ? "" : dstDir) + "/" + e.name();
                    if (srcM.id() == dstM.id() && Paths.overlap(p, dstPath) && e.isDirectory()
                            && Paths.sameOrInside(dstPath, p)) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.destino-dentro-da-origem",
                                Map.of("origem", p, "destino", dstPath));
                    }
                    visit(c, e, dstPath);
                }
            } catch (IOException e) {
                sl.discard();
                dl.discard();
                throw gateway(e);
            }
            long files = c.planned.stream().filter(x -> "FILE".equals(x.kind()) && "QUEUED".equals(x.status())).count();
            long bytes = c.planned.stream().filter(x -> "FILE".equals(x.kind()) && "QUEUED".equals(x.status()))
                    .mapToLong(Planned::size).sum();
            long skipped = c.planned.stream().filter(x -> "SKIPPED".equals(x.status())).count();
            List<String> warnings = new ArrayList<>();
            var caps = factory.capabilitiesOf(dstM);
            if (!caps.preservesMode() || !caps.preservesLinks()) {
                warnings.add("destino-fidelidade-parcial");
            }
            UUID batch = null;
            if (persist && c.conflicts.isEmpty()) {
                batch = persist(ownerId, srcM, dstM, c.planned);
            }
            return new PlanResult(c.planned.size(), files, bytes, skipped, c.ignored, c.conflicts, warnings,
                    batch == null ? null : batch.toString());
        } catch (IOException e) {
            throw gateway(e);
        }
    }

    private void visit(Ctx c, FileEntry e, String dstPath) throws IOException {
        String parent = FsOps.parentOf(dstPath);
        FileEntry existing = c.listing(parent).get(e.name());
        long mtime = e.mtime().toEpochMilli();
        switch (e.type()) {
            case SPECIAL -> c.planned.add(new Planned("FILE", e.path(), dstPath, 0, mtime, e.mode(), null,
                    TransferItem.SKIPPED, "item-especial", false, null));
            case DIRECTORY -> {
                if (existing != null && existing.type() != FileType.DIRECTORY) {
                    throw new ApiException(HttpStatus.CONFLICT, "transferencia.tipo-incompativel",
                            Map.of("caminho", dstPath));
                }
                if (existing == null) {
                    c.planned.add(new Planned("DIR", e.path(), dstPath, 0, mtime, e.mode(), null,
                            TransferItem.QUEUED, null, false, null));
                }
                for (FileEntry child : c.src.list(e.path())) {
                    if (c.applyFilters && c.filter.hides(subject(child))) {
                        c.ignored += 1 + countDescendants(c.src, child);
                        continue;
                    }
                    visit(c, child, dstPath + "/" + child.name());
                }
            }
            default -> visitLeaf(c, e, dstPath, existing, mtime);
        }
    }

    private long countDescendants(FileGateway gw, FileEntry e) throws IOException {
        if (!e.isDirectory()) {
            return 0;
        }
        long n = 0;
        for (FileEntry child : gw.list(e.path())) {
            n += 1 + countDescendants(gw, child);
        }
        return n;
    }

    private void visitLeaf(Ctx c, FileEntry e, String dstPath, FileEntry existing, long mtime) throws IOException {
        boolean link = e.type() == FileType.SYMLINK;
        if (link && !c.linksSupported) {
            c.planned.add(new Planned("SYMLINK", e.path(), dstPath, 0, mtime, e.mode(), e.linkTarget(),
                    TransferItem.SKIPPED, "destino-sem-links", false, null));
            return;
        }
        String kind = link ? "SYMLINK" : "FILE";
        long size = link ? 0 : e.size();
        if (existing == null) {
            c.planned.add(new Planned(kind, e.path(), dstPath, size, mtime, e.mode(), e.linkTarget(),
                    TransferItem.QUEUED, null, false, null));
            return;
        }
        if (existing.type() == FileType.DIRECTORY) {
            throw new ApiException(HttpStatus.CONFLICT, "transferencia.tipo-incompativel", Map.of("caminho", dstPath));
        }
        ConflictAction a = action(c.decisions.get(dstPath));
        if (a == null) {
            a = c.defaultAction;
        }
        if (a == null) {
            c.conflicts.add(new Conflict(e.path(), dstPath, size, mtime, existing.size(),
                    existing.mtime().toEpochMilli()));
            return;
        }
        ConflictPolicy.Decision d = ConflictPolicy.resolve(a, new ConflictPolicy.Meta(size, mtime),
                new ConflictPolicy.Meta(existing.size(), existing.mtime().toEpochMilli()));
        switch (d) {
            case WRITE -> c.planned.add(new Planned(kind, e.path(), dstPath, size, mtime, e.mode(), e.linkTarget(),
                    TransferItem.QUEUED, null, false, a.name()));
            case RESUME -> c.planned.add(new Planned(kind, e.path(), dstPath, size, mtime, e.mode(), e.linkTarget(),
                    TransferItem.QUEUED, null, !link, a.name()));
            case SKIP -> c.planned.add(new Planned(kind, e.path(), dstPath, size, mtime, e.mode(), e.linkTarget(),
                    TransferItem.SKIPPED, a == ConflictAction.SKIP ? "pulado-pela-politica" : "destino-igual-ou-mais-novo",
                    false, a.name()));
            case RENAME -> {
                String parent = FsOps.parentOf(dstPath);
                Map<String, FileEntry> names = c.listing(parent);
                String free = ConflictPolicy.renamed(e.name(),
                        n -> names.containsKey(n) || c.taken.contains(parent + "/" + n));
                c.taken.add(parent + "/" + free);
                c.planned.add(new Planned(kind, e.path(), parent + "/" + free, size, mtime, e.mode(), e.linkTarget(),
                        TransferItem.QUEUED, null, false, a.name()));
            }
        }
    }

    /** Mostra o que será feito, os conflitos a decidir e quantos itens o filtro ignora; não grava nada. */
    public PlanResult plan(long ownerId, PlanRequest req) {
        return expand(ownerId, req, false);
    }

    @Transactional
    public PlanResult start(long ownerId, PlanRequest req) {
        PlanResult r = expand(ownerId, req, true);
        if (!r.conflicts().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "transferencia.conflitos-sem-decisao",
                    Map.of("conflitos", r.conflicts().size()));
        }
        audit.record("TRANSFER_START", req.srcPaths().size() + " item(ns)", AuditService.SUCESSO,
                Map.of("origem", req.srcMachineId(), "destino", req.dstMachineId(), "itens", r.items()));
        return r;
    }

    private UUID persist(long ownerId, Machine src, Machine dst, List<Planned> planned) {
        UUID batch = UUID.randomUUID();
        for (Planned p : planned) {
            long id = repository.insert(new TransferRepository.NewItem(ownerId, batch, "COPY", p.kind(), p.status(),
                    src.id(), src.name(), p.srcPath(), dst.id(), dst.name(), p.dstPath(), p.size(), p.mtime(), p.mode(),
                    p.link(), p.appendFinal(), p.action(), p.skipReason()));
            if (TransferItem.QUEUED.equals(p.status())) {
                repository.insertOutbox(id);
            }
        }
        kickAfterCommit();
        return batch;
    }

    private void kickAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outbox.kick();
                }
            });
        } else {
            outbox.kick();
        }
    }

    // ---- fila: cancelar, retomar, repetir ----

    public List<TransferItem> queue(long ownerId, String status, UUID batch, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 500);
        return repository.list(ownerId, status, batch, limit, Math.max(page, 0) * limit);
    }

    public List<TransferItem> operations(long ownerId, int limit) {
        return repository.operations(ownerId, Math.min(Math.max(limit, 1), 500));
    }

    private TransferItem require(long ownerId, long id) {
        return repository.find(ownerId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transferencia.nao-encontrada"));
    }

    private void publish(TransferItem i, String status) {
        bus.publish("transfer." + i.ownerId(), Map.of("type", "item", "id", i.id(), "batchId", i.batchId().toString(),
                "status", status, "bytesDone", i.bytesDone(), "size", i.size(), "speed", 0));
    }

    public void cancel(long ownerId, long id) {
        TransferItem i = require(ownerId, id);
        if (TransferItem.QUEUED.equals(i.status())) {
            if (repository.cancelQueued(id)) {
                publish(i, TransferItem.CANCELED);
            }
        } else if (TransferItem.TRANSFERRING.equals(i.status())) {
            running.cancel(id);
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "transferencia.nao-cancelavel", Map.of("status", i.status()));
        }
    }

    /** "Parar tudo": cancela o que aguarda e interrompe o que está em andamento. */
    public int cancelAll(long ownerId) {
        int n = 0;
        for (TransferItem i : repository.queued(ownerId)) {
            if (repository.cancelQueued(i.id())) {
                publish(i, TransferItem.CANCELED);
                n++;
            }
        }
        for (TransferItem i : repository.running(ownerId)) {
            if (running.cancel(i.id())) {
                n++;
            }
        }
        audit.record("TRANSFER_STOP_ALL", "transferencias", AuditService.SUCESSO, Map.of("itens", n));
        return n;
    }

    /** Retoma (do ponto em que parou, se a origem não mudou) ou repete um item interrompido, cancelado ou falho. */
    @Transactional
    public void resume(long ownerId, long id) {
        TransferItem i = require(ownerId, id);
        if (!i.restartable()) {
            throw new ApiException(HttpStatus.CONFLICT, "transferencia.nao-reiniciavel", Map.of("status", i.status()));
        }
        if (i.dstMachineId() != null) {
            guard.check(i.dstMachineId(), i.dstPath());
        }
        if (repository.requeue(id, i.resumeOffset(), i.note())) {
            repository.insertOutbox(id);
            kickAfterCommit();
        }
    }

    /** "Repetir falhas" ({@code FAILED}) e "Retomar interrompidos" ({@code INTERRUPTED}, {@code CANCELED}). */
    @Transactional
    public int resumeAll(long ownerId, List<String> statuses) {
        int n = 0;
        for (String st : statuses) {
            for (TransferItem i : repository.restartable(ownerId, st)) {
                try {
                    if (i.dstMachineId() != null) {
                        guard.check(i.dstMachineId(), i.dstPath());
                    }
                } catch (ApiException e) {
                    continue; // destino em uso por um job: este item continua como está
                }
                if (repository.requeue(i.id(), i.resumeOffset(), i.note())) {
                    repository.insertOutbox(i.id());
                    n++;
                }
            }
        }
        if (n > 0) {
            kickAfterCommit();
        }
        return n;
    }

    // ---- operações de arquivo ----

    private void record(long ownerId, String op, Machine m, String from, String to, long count, String status,
            String errorCode) {
        long id = repository.insert(new TransferRepository.NewItem(ownerId, UUID.randomUUID(), op, "FILE",
                TransferItem.COMPLETED, m.id(), m.name(), from, m.id(), m.name(), to, count, null, null, null, false,
                null, null));
        if (!TransferItem.COMPLETED.equals(status)) {
            repository.finish(id, status, 0, 0, null, errorCode, null, null);
        }
    }

    public void mkdir(long ownerId, long machineId, String path) {
        Machine m = writable(ownerId, machineId);
        String p = normalize(path);
        guard.check(m.id(), p);
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            try {
                if (lease.gateway().lstatIfExists(p).isPresent()) {
                    throw new ApiException(HttpStatus.CONFLICT, "transferencia.destino-existe", Map.of("caminho", p));
                }
                lease.gateway().mkdirs(p);
            } catch (IOException e) {
                lease.discard();
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        record(ownerId, "MKDIR", m, p, p, 1, TransferItem.COMPLETED, null);
        audit.record("FS_MKDIR", m.name() + ":" + p, AuditService.SUCESSO);
    }

    public void rename(long ownerId, long machineId, String from, String to) {
        Machine m = writable(ownerId, machineId);
        String a = normalize(from);
        String b = normalize(to);
        guard.check(m.id(), a);
        guard.check(m.id(), b);
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            try {
                FileGateway gw = lease.gateway();
                if (gw.lstatIfExists(b).isPresent()) {
                    throw new ApiException(HttpStatus.CONFLICT, "transferencia.destino-existe", Map.of("caminho", b));
                }
                gw.rename(a, b);
            } catch (IOException e) {
                lease.discard();
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        record(ownerId, "RENAME", m, a, b, 1, TransferItem.COMPLETED, null);
        audit.record("FS_RENAME", m.name() + ":" + a, AuditService.SUCESSO, Map.of("para", b));
    }

    /** Quantos itens a exclusão atinge (o próprio item e, num diretório, tudo que há dentro dele). */
    public long countItems(long ownerId, long machineId, String path) {
        Machine m = machine(ownerId, machineId);
        String p = normalize(path);
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            try {
                FileGateway gw = lease.gateway();
                FileEntry e = gw.lstat(p);
                return 1 + countDescendants(gw, e);
            } catch (IOException e) {
                lease.discard();
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
    }

    public long delete(long ownerId, long machineId, String path, boolean confirmed) {
        if (!confirmed) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transferencia.exclusao-sem-confirmacao");
        }
        Machine m = writable(ownerId, machineId);
        String p = normalize(path);
        if (p.equals("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorio-raiz", Map.of("diretorio", p));
        }
        guard.check(m.id(), p);
        long count;
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            try {
                FileGateway gw = lease.gateway();
                FileEntry e = gw.lstat(p);
                count = 1 + countDescendants(gw, e);
                FsOps.deleteTree(gw, p);
            } catch (IOException e) {
                lease.discard();
                record(ownerId, "DELETE", m, p, p, 0, TransferItem.FAILED, "transferencia.falha");
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        record(ownerId, "DELETE", m, p, p, count, TransferItem.COMPLETED, null);
        audit.record("FS_DELETE", m.name() + ":" + p, AuditService.SUCESSO, Map.of("itens", count));
        return count;
    }

    // ---- conjuntos de filtros ----

    public List<TransferRepository.FilterSet> filterSets(long ownerId) {
        return repository.filterSets(ownerId);
    }

    public long saveFilterSet(long ownerId, String name, TransferFilter.Definition def) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.nome-invalido");
        }
        TransferFilter.compile(def); // valida
        return repository.saveFilterSet(ownerId, name.strip(), json.writeValueAsString(def));
    }

    public void deleteFilterSet(long ownerId, long id) {
        if (!repository.deleteFilterSet(ownerId, id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "filtro.nao-encontrado");
        }
    }

    // ---- comparação de pastas ----

    /** Árvore de um só nível: o comparador enxerga apenas os filhos diretos do diretório aberto (já filtrados). */
    private static final class ShallowTree implements TreeSource {
        private final GatewayTree inner;
        private final TransferFilter filter;
        private final FileGateway gw;
        private final String root;

        ShallowTree(FileGateway gw, String root, String label, TransferFilter filter) {
            this.inner = new GatewayTree(gw, root, label);
            this.gw = gw;
            this.root = root;
            this.filter = filter;
        }

        @Override
        public Optional<Node> root() throws IOException {
            return inner.root();
        }

        @Override
        public List<Node> children(String relDir) throws IOException {
            if (!relDir.isEmpty()) {
                return List.of();
            }
            List<Node> out = new ArrayList<>();
            try {
                for (FileEntry e : gw.list(root)) {
                    if (!filter.hides(subject(e))) {
                        out.add(Node.of(e));
                    }
                }
            } catch (NoSuchFileException ignorado) {
                return List.of();
            }
            return out;
        }

        @Override
        public String sha256(String relPath, Node node) throws IOException {
            return inner.sha256(relPath, node);
        }

        @Override
        public boolean preservesMode() {
            return false; // permissões não entram na comparação de painéis
        }

        @Override
        public boolean preservesLinks() {
            return false;
        }

        @Override
        public String describe() {
            return inner.describe();
        }
    }

    public CompareResult compare(long ownerId, CompareRequest req) {
        if (req.left() == null || req.right() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "comparacao.parametros-invalidos");
        }
        String l = req.left().filter() == null ? "{}" : json.writeValueAsString(req.left().filter());
        String r = req.right().filter() == null ? "{}" : json.writeValueAsString(req.right().filter());
        boolean noFilterL = req.left().filter() == null || req.left().filter().conditions() == null
                || req.left().filter().conditions().isEmpty();
        boolean noFilterR = req.right().filter() == null || req.right().filter().conditions() == null
                || req.right().filter().conditions().isEmpty();
        if (!(noFilterL && noFilterR) && !l.equals(r)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "comparacao.filtros-diferentes");
        }
        boolean bySize = "SIZE".equalsIgnoreCase(req.mode());
        if (!bySize && !"MTIME".equalsIgnoreCase(req.mode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "comparacao.modo-invalido");
        }
        long tolerance = req.toleranceMinutes() == null || req.toleranceMinutes() <= 0
                ? TreeComparator.MTIME_TOLERANCE_MILLIS : req.toleranceMinutes() * 60_000L;
        Machine lm = machine(ownerId, req.left().machineId());
        Machine rm = machine(ownerId, req.right().machineId());
        TransferFilter filter = TransferFilter.compile(req.left().filter());
        String lp = normalize(req.left().path());
        String rp = normalize(req.right().path());
        Map<String, String[]> states = new LinkedHashMap<>();
        long[] counts = new long[2];
        try (GatewayPool.Lease ll = pool.acquire(lm); GatewayPool.Lease rl = pool.acquire(rm)) {
            try {
                comparator.compare(new ShallowTree(ll.gateway(), lp, lm.name(), filter),
                        new ShallowTree(rl.gateway(), rp, rm.name(), filter), TreeComparator.Mode.QUICK, tolerance,
                        new CancellationToken(), new TreeComparator.Visitor() {
                            @Override
                            public void onlySource(String rel, Node n) {
                                states.put(rel, new String[] {"ONLY_HERE", "ABSENT"});
                                counts[0]++;
                            }

                            @Override
                            public void onlyTarget(String rel, Node n) {
                                states.put(rel, new String[] {"ABSENT", "ONLY_HERE"});
                                counts[0]++;
                            }

                            @Override
                            public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> reasons) {
                                if (reasons.contains(TreeComparator.Reason.TYPE)) {
                                    states.put(rel, new String[] {"TYPE_DIFFERENT", "TYPE_DIFFERENT"});
                                } else if (bySize && reasons.contains(TreeComparator.Reason.SIZE)) {
                                    states.put(rel, new String[] {"SIZE_DIFFERENT", "SIZE_DIFFERENT"});
                                } else if (!bySize && reasons.contains(TreeComparator.Reason.MTIME)) {
                                    boolean leftNewer = s.mtimeMillis() > t.mtimeMillis();
                                    states.put(rel, new String[] {leftNewer ? "NEWER" : "OLDER",
                                            leftNewer ? "OLDER" : "NEWER"});
                                } else {
                                    states.put(rel, new String[] {"SAME", "SAME"});
                                    counts[1]++;
                                    return;
                                }
                                counts[0]++;
                            }

                            @Override
                            public void same(String rel, Node s, Node t) {
                                states.put(rel, new String[] {"SAME", "SAME"});
                                counts[1]++;
                            }
                        });
            } catch (IOException e) {
                ll.discard();
                rl.discard();
                throw gateway(e);
            }
        } catch (IOException e) {
            throw gateway(e);
        }
        List<CompareEntry> entries = new ArrayList<>();
        states.forEach((name, s) -> entries.add(new CompareEntry(name, s[0], s[1])));
        return new CompareResult(entries, counts[0], counts[1]);
    }
}
