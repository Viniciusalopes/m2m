package br.com.vovolinux.m2m.verify;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobController;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles/{id}")
public class VerifyController {

    public record VerifyRequest(@NotNull Long destMachineId, @NotNull TreeComparator.Mode mode) {
    }

    public record Target(long id, String name, String protocol) {
    }

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final JobService jobService;
    private final JobController jobs;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public VerifyController(ProfileRepository profiles, MachineRepository machines, JobService jobService,
            JobController jobs, CurrentUser currentUser, AuditService audit) {
        this.profiles = profiles;
        this.machines = machines;
        this.jobService = jobService;
        this.jobs = jobs;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    private long owner() {
        return currentUser.require().id();
    }

    /** Máquinas com mapeamento para todos os diretórios do perfil (candidatas a destino da verificação). */
    @GetMapping("/verify-targets")
    public List<Target> targets(@PathVariable long id) {
        long owner = owner();
        profiles.find(owner, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "perfil.nao-encontrado"));
        List<ProfileRepository.DirRow> dirs = profiles.dirs(id);
        return machines.findAll(owner).stream()
                .filter(m -> !dirs.isEmpty()
                        && dirs.stream().allMatch(d -> d.mappings().stream().anyMatch(x -> x.machineId() == m.id())))
                .map(m -> new Target(m.id(), m.name(), m.protocol().name())).toList();
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView verify(@PathVariable long id, @Valid @RequestBody VerifyRequest req) {
        long owner = owner();
        var profile = profiles.find(owner, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "perfil.nao-encontrado"));
        Machine dest = machines.find(owner, req.destMachineId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
        List<String> sem = profiles.dirs(id).stream()
                .filter(d -> d.mappings().stream().noneMatch(m -> m.machineId() == dest.id()))
                .map(ProfileRepository.DirRow::path).toList();
        if (!sem.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "verificacao.sem-mapeamento",
                    Map.of("diretorios", sem, "maquina", dest.name()));
        }
        Map<String, Object> params = new HashMap<>();
        params.put("profileId", id);
        params.put("destMachineId", dest.id());
        params.put("mode", req.mode().name());
        Job job = jobService.enqueue(owner, JobType.VERIFY, "MANUAL", id, profile.name(), params);
        audit.record("VERIFY_START", profile.name(), AuditService.SUCESSO,
                Map.of("destino", dest.name(), "modo", req.mode().name(), "jobId", job.id()));
        return jobs.view(job);
    }
}
