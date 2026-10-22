package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.job.JobController;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Configurações de manutenção e disparo manual de verificação, backup do banco e reindexação. */
@RestController
@RequestMapping("/api")
public class MaintenanceController {

    public record Settings(MaintenanceSettings.Verify verify, MaintenanceSettings.DbBackup dbBackup) {
    }

    public record ReindexRequest(Long machineId, String dir) {
    }

    private final MaintenanceSettings settings;
    private final JobService jobs;
    private final JobController jobController;
    private final MachineRepository machines;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public MaintenanceController(MaintenanceSettings settings, JobService jobs, JobController jobController,
            MachineRepository machines, CurrentUser currentUser, AuditService audit, ApplicationEventPublisher events) {
        this.settings = settings;
        this.jobs = jobs;
        this.jobController = jobController;
        this.machines = machines;
        this.currentUser = currentUser;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping("/settings/maintenance")
    public Settings get() {
        return new Settings(settings.verify(), settings.dbBackup());
    }

    @PutMapping("/settings/maintenance")
    public Settings put(@RequestBody Settings s) {
        if (s.verify() == null || s.dbBackup() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validacao.campos-obrigatorios");
        }
        settings.saveVerify(s.verify());
        settings.saveDbBackup(s.dbBackup());
        audit.record("MAINTENANCE_SETTINGS", "manutencao", AuditService.SUCESSO,
                Map.of("verificacao", s.verify().enabled(), "backupBanco", s.dbBackup().enabled()));
        events.publishEvent(new MaintenanceScheduler.SettingsChanged());
        return get();
    }

    @PostMapping("/maintenance/verify-archives")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView verify() {
        return jobController.view(jobs.enqueue(currentUser.require().id(), JobType.ARCHIVE_VERIFY, "MANUAL", null,
                null, Map.of()));
    }

    @PostMapping("/maintenance/db-backup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView dbBackup() {
        audit.record("DB_BACKUP_START", "banco", AuditService.SUCESSO);
        return jobController.view(jobs.enqueue(currentUser.require().id(), JobType.DB_BACKUP, "MANUAL", null, null,
                Map.of()));
    }

    @PostMapping("/maintenance/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView reindex(@RequestBody ReindexRequest req) {
        long owner = currentUser.require().id();
        if (req.machineId() == null || req.dir() == null || !req.dir().startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reindexacao.parametros-invalidos");
        }
        machines.find(owner, req.machineId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
        Map<String, Object> params = new HashMap<>();
        params.put("machineId", req.machineId());
        params.put("dir", req.dir());
        audit.record("REINDEX_START", req.dir(), AuditService.SUCESSO, Map.of("maquina", req.machineId()));
        return jobController.view(jobs.enqueue(owner, JobType.REINDEX, "MANUAL", null, null, params));
    }
}
