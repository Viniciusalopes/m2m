package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayFactory;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Fidelity;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.Paths;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Regras de negócio da sincronização: prévia, início, agendamentos, confirmação e desfazer. */
@Service
public class SyncService {

    public record SyncRequest(Long destMachineId, Boolean compareContent, Boolean confirmed, Boolean confirmEmptySource,
            Boolean confirmDestinationChanges) {
    }

    public record DirPreview(int dir, String sourcePath, String targetPath, boolean targetExists, long create,
            long update, long delete) {
    }

    public record Preview(List<DirPreview> directories, long create, long update, long delete, boolean alreadySynced,
            long emptySourceDeletes, boolean hasState, long destinationChanges, List<String> destinationSample,
            List<String> warnings, String destMachine) {
    }

    public record ScheduleRequest(Long destMachineId, String time, Boolean enabled, Integer maxDeletionsPercent,
            Integer maxDeletionsAbs) {
    }

    public record ScheduleView(long id, long destMachineId, String destMachineName, String time, boolean enabled,
            int maxDeletionsPercent, int maxDeletionsAbs, boolean hasManualSync) {
    }

    /** Publicado ao mudar os agendamentos de sincronização do perfil. */
    public record SyncScheduleChanged(long profileId) {
    }

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final GatewayFactory factory;
    private final SyncPlanner planner;
    private final SyncStateService states;
    private final SyncRunRepository runs;
    private final SyncScheduleRepository schedules;
    private final JobService jobService;
    private final JobRepository jobRepository;
    private final BackupArchiveRepository archives;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Path tmpRoot;

    public SyncService(ProfileRepository profiles, MachineRepository machines, GatewayPool pool,
            GatewayFactory factory, SyncPlanner planner, SyncStateService states, SyncRunRepository runs,
            SyncScheduleRepository schedules, JobService jobService, JobRepository jobRepository,
            BackupArchiveRepository archives, AuditService audit, ApplicationEventPublisher events, JsonMapper json,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.profiles = profiles;
        this.machines = machines;
        this.pool = pool;
        this.factory = factory;
        this.planner = planner;
        this.states = states;
        this.runs = runs;
        this.schedules = schedules;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.archives = archives;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.tmpRoot = Path.of(tmpDir);
    }

    private ProfileRepository.Row profile(long ownerId, long id) {
        return profiles.find(ownerId, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "perfil.nao-encontrado"));
    }

    private Machine destination(long ownerId, ProfileRepository.Row p, Long destId) {
        if (destId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente");
        }
        Machine m = machines.find(ownerId, destId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
        if (m.id() == p.sourceMachineId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.destino-igual-origem");
        }
        if (!factory.capabilitiesOf(m).writable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "restauracao.destino-somente-leitura", Map.of("maquina", m.name()));
        }
        return m;
    }

    private List<SyncPlanner.DirPair> pairs(ProfileRepository.Row p, Machine dest) {
        List<SyncPlanner.DirPair> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (ProfileRepository.DirRow d : profiles.dirs(p.id())) {
            var m = d.mappings().stream().filter(x -> x.machineId() == dest.id()).findFirst();
            if (m.isEmpty()) {
                missing.add(d.path());
            } else {
                out.add(new SyncPlanner.DirPair(d.position(), d.path(), m.get().path()));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.destino-sem-mapeamento",
                    Map.of("diretorios", missing, "maquina", dest.name()));
        }
        if (out.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "backup.perfil-sem-diretorios");
        }
        return out;
    }

    // ---- prévia ----

    public Preview preview(long ownerId, long profileId, SyncRequest req) {
        ProfileRepository.Row p = profile(ownerId, profileId);
        Machine dest = destination(ownerId, p, req.destMachineId());
        Machine source = machines.find(ownerId, p.sourceMachineId()).orElseThrow();
        List<SyncPlanner.DirPair> pairs = pairs(p, dest);
        Path tmp = tmpRoot.resolve("preview-" + java.util.UUID.randomUUID());
        try {
            Files.createDirectories(tmp);
            try (GatewayPool.Lease sl = pool.acquire(source); GatewayPool.Lease dl = pool.acquire(dest)) {
                FileGateway src = sl.gateway();
                FileGateway dst = dl.gateway();
                CancellationToken token = new CancellationToken();
                SyncPlan plan = planner.plan(src, dst, source.name(), dest.name(), pairs,
                        Boolean.TRUE.equals(req.compareContent()), tmp.resolve("plan.ndjson"), token);
                List<DirPreview> dirs = plan.directories().stream().map(d -> new DirPreview(d.dir(), d.sourcePath(),
                        d.targetPath(), d.targetExists(), d.create(), d.update() + d.replace(), d.delete())).toList();
                long emptyDeletes = plan.directories().stream().filter(d -> d.sourceItems() == 0 && d.targetItems() > 0)
                        .mapToLong(SyncPlan.DirSummary::targetItems).sum();
                var changes = states.changesSince(p.id(), dest.id(), dst,
                        pairs.stream().map(SyncPlanner.DirPair::targetPath).toList(), token);
                List<String> warnings = new ArrayList<>();
                Fidelity f = Fidelity.of(dest.protocol(), factory.capabilitiesOf(dest));
                if (!f.permissions() || !f.symlinks()) {
                    warnings.add("destino-fidelidade-parcial");
                }
                if (plan.specialsIgnored() > 0) {
                    warnings.add("itens-especiais-ignorados");
                }
                return new Preview(dirs, plan.create(), plan.update() + plan.replace(), plan.delete(), plan.empty(),
                        emptyDeletes, changes.isPresent(), changes.map(SyncStateService.Changes::total).orElse(0L),
                        changes.map(c -> c.sample(50)).orElse(List.of()), warnings, dest.name());
            }
        } catch (JobFailure e) {
            throw new ApiException(HttpStatus.CONFLICT, e.codigo(), e.parametros());
        } catch (GatewayException e) {
            throw new ApiException(e.codigo().startsWith("identidade.") ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    e.codigo(), e.parametros());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "conexao.falha", Map.of("detalhe", String.valueOf(e.getMessage())));
        } finally {
            deleteTree(tmp);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignorado) {
            // limpeza best-effort
        }
    }

    // ---- início ----

    public Job start(long ownerId, long profileId, SyncRequest req) {
        if (!Boolean.TRUE.equals(req.confirmed())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.confirmacao-obrigatoria");
        }
        ProfileRepository.Row p = profile(ownerId, profileId);
        Machine dest = destination(ownerId, p, req.destMachineId());
        pairs(p, dest); // valida o mapeamento
        Map<String, Object> params = new HashMap<>();
        params.put("profileId", profileId);
        params.put("destMachineId", dest.id());
        params.put("compareContent", Boolean.TRUE.equals(req.compareContent()));
        params.put("confirmEmptySource", Boolean.TRUE.equals(req.confirmEmptySource()));
        params.put("confirmDestinationChanges", Boolean.TRUE.equals(req.confirmDestinationChanges()));
        Job job = jobService.enqueue(ownerId, JobType.SYNC, "MANUAL", profileId, p.name(), params);
        audit.record("SYNC_START", p.name(), AuditService.SUCESSO, Map.of("destino", dest.name(), "jobId", job.id(),
                "gatilho", "MANUAL"));
        return job;
    }

    /** O usuário confirma uma sincronização agendada que parou no limite de exclusões: roda como manual. */
    public Job confirmAwaiting(long ownerId, long jobId, SyncRequest flags) {
        Job j = jobService.require(ownerId, jobId);
        if (!JobType.SYNC.equals(j.type()) || !JobStatus.AWAITING_CONFIRMATION.equals(j.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "sincronizacao.nao-aguarda-confirmacao");
        }
        JsonNode old = json.readTree(j.params());
        Map<String, Object> params = new HashMap<>();
        params.put("profileId", old.get("profileId").asLong());
        params.put("destMachineId", old.get("destMachineId").asLong());
        params.put("compareContent", false);
        params.put("confirmEmptySource", flags != null && Boolean.TRUE.equals(flags.confirmEmptySource()));
        params.put("confirmDestinationChanges", flags != null && Boolean.TRUE.equals(flags.confirmDestinationChanges()));
        params.put("confirmedLimit", true);
        Job novo = jobService.enqueue(ownerId, JobType.SYNC, "MANUAL", j.profileId(), j.profileName(), params);
        jobRepository.finish(j.id(), JobStatus.CANCELED, j.progress() == null ? "{}" : j.progress(),
                json.writeValueAsString(Map.of("confirmedBy", novo.id())));
        audit.record("SYNC_CONFIRM", j.profileName(), AuditService.SUCESSO, Map.of("jobId", novo.id(), "original", j.id()));
        return novo;
    }

    public Job undo(long ownerId, long archiveId) {
        BackupArchive a = archives.find(ownerId, archiveId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "backup.nao-encontrado"));
        if (!"PRE_SYNC".equals(a.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.nao-e-backup-de-seguranca");
        }
        if (!ArchiveStatus.usable(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel", Map.of("status", a.status()));
        }
        Map<String, Object> params = new HashMap<>();
        params.put("archiveId", archiveId);
        Job job = jobService.enqueue(ownerId, JobType.SYNC_UNDO, "MANUAL", a.profileId(), a.profileNameSnapshot(), params);
        audit.record("SYNC_UNDO", a.fileName(), AuditService.SUCESSO, Map.of("jobId", job.id()));
        return job;
    }

    public List<SyncRunRepository.Row> history(long ownerId, Long profileId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return runs.list(ownerId, profileId, limit, Math.max(page, 0) * limit);
    }

    // ---- agendamentos ----

    public List<ScheduleView> schedules(long ownerId, long profileId) {
        profile(ownerId, profileId);
        return schedules.byProfile(profileId).stream().map(s -> view(ownerId, s)).toList();
    }

    private ScheduleView view(long ownerId, SyncScheduleRepository.Row s) {
        String name = machines.find(ownerId, s.destMachineId()).map(Machine::name).orElse("?");
        return new ScheduleView(s.id(), s.destMachineId(), name, s.time().toString(), s.enabled(),
                s.maxDeletionsPercent(), s.maxDeletionsAbs(), runs.hasCompletedManual(s.profileId(), s.destMachineId()));
    }

    public ScheduleView saveSchedule(long ownerId, long profileId, Long scheduleId, ScheduleRequest req) {
        ProfileRepository.Row p = profile(ownerId, profileId);
        Machine dest = destination(ownerId, p, req.destMachineId());
        List<SyncPlanner.DirPair> pairs = pairs(p, dest);
        LocalTime time;
        try {
            time = LocalTime.parse(req.time());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.horario-invalido");
        }
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        int pct = req.maxDeletionsPercent() == null ? 20 : req.maxDeletionsPercent();
        int abs = req.maxDeletionsAbs() == null ? 500 : req.maxDeletionsAbs();
        if (pct < 0 || pct > 100 || abs < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.limite-invalido");
        }
        Optional<SyncScheduleRepository.Row> existing = scheduleId == null
                ? schedules.findByProfileAndDest(profileId, dest.id()) : schedules.find(scheduleId);
        if (scheduleId != null && existing.filter(s -> s.profileId() == profileId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "sincronizacao.agendamento-nao-encontrado");
        }
        if (scheduleId == null && existing.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "sincronizacao.agendamento-duplicado", Map.of("destino", dest.name()));
        }
        if (enabled) {
            if (!runs.hasCompletedManual(profileId, dest.id())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "sincronizacao.ativar-exige-manual-previa",
                        Map.of("destino", dest.name()));
            }
            checkOpposite(ownerId, p, dest, pairs);
        }
        long id;
        if (existing.isPresent()) {
            id = existing.get().id();
            schedules.update(id, time, enabled, pct, abs);
        } else {
            id = schedules.insert(profileId, dest.id(), time, enabled, pct, abs);
        }
        audit.record("SYNC_SCHEDULE_SAVE", p.name(), AuditService.SUCESSO,
                Map.of("destino", dest.name(), "ativo", enabled, "horario", time.toString()));
        events.publishEvent(new SyncScheduleChanged(profileId));
        return view(ownerId, schedules.find(id).orElseThrow());
    }

    public void deleteSchedule(long ownerId, long profileId, long scheduleId) {
        ProfileRepository.Row p = profile(ownerId, profileId);
        var s = schedules.find(scheduleId).filter(x -> x.profileId() == profileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "sincronizacao.agendamento-nao-encontrado"));
        schedules.delete(s.id());
        audit.record("SYNC_SCHEDULE_DELETE", p.name(), AuditService.SUCESSO, Map.of());
        events.publishEvent(new SyncScheduleChanged(profileId));
    }

    /** Sincronizações em sentidos opostos sobre os mesmos caminhos (A → B e B → A) são proibidas. */
    private void checkOpposite(long ownerId, ProfileRepository.Row p, Machine dest, List<SyncPlanner.DirPair> pairs) {
        for (SyncScheduleRepository.Row other : schedules.allExcept(p.id())) {
            if (!other.enabled()) {
                continue;
            }
            var op = profiles.findById(other.profileId());
            if (op.isEmpty() || op.get().ownerId() != ownerId) {
                continue;
            }
            // o outro lê da máquina que é o NOSSO destino e grava na que é a NOSSA origem
            if (op.get().sourceMachineId() != dest.id() || other.destMachineId() != p.sourceMachineId()) {
                continue;
            }
            for (ProfileRepository.DirRow od : profiles.dirs(other.profileId())) {
                var omap = od.mappings().stream().filter(m -> m.machineId() == other.destMachineId()).findFirst();
                if (omap.isEmpty()) {
                    continue;
                }
                for (SyncPlanner.DirPair mine : pairs) {
                    if (Paths.overlap(od.path(), mine.targetPath()) && Paths.overlap(omap.get().path(), mine.sourcePath())) {
                        throw new ApiException(HttpStatus.CONFLICT, "sincronizacao.sentido-contrario",
                                Map.of("perfil", op.get().name(), "origem", od.path(), "destino", omap.get().path()));
                    }
                }
            }
        }
    }
}
