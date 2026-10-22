package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.Paths;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recusa gravar, renomear ou excluir onde uma restauração ou sincronização em andamento está espelhando (D22): mexer
 * ali no meio do job corromperia o resultado.
 */
@Component
public class TransferGuard {

    private record Root(long machineId, String path) {
    }

    private final JobRepository jobs;
    private final ProfileRepository profiles;
    private final BackupArchiveRepository archives;
    private final MachineRepository machines;
    private final JsonMapper json;

    public TransferGuard(JobRepository jobs, ProfileRepository profiles, BackupArchiveRepository archives,
            MachineRepository machines, JsonMapper json) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.archives = archives;
        this.machines = machines;
        this.json = json;
    }

    /** @throws ApiException 409 {@code transferencia.destino-em-uso} se algum job em execução usa esse caminho */
    public void check(long machineId, String path) {
        for (Job j : jobs.active()) {
            if (!JobStatus.RUNNING.equals(j.status())) {
                continue;
            }
            for (Root r : rootsOf(j)) {
                if (r.machineId() == machineId && Paths.overlap(r.path(), path)) {
                    throw new ApiException(HttpStatus.CONFLICT, "transferencia.destino-em-uso", Map.of("jobId", j.id(),
                            "tipo", j.type(), "perfil", String.valueOf(j.profileName()), "caminho", r.path()));
                }
            }
        }
    }

    private List<Root> rootsOf(Job j) {
        List<Root> roots = new ArrayList<>();
        try {
            JsonNode p = json.readTree(j.params());
            switch (j.type()) {
                case JobType.RESTORE -> {
                    for (JsonNode t : p.path("targets")) {
                        roots.add(new Root(p.path("machineId").asLong(), t.path("path").asText()));
                    }
                }
                case JobType.SELECTIVE_RESTORE ->
                    roots.add(new Root(p.path("machineId").asLong(), p.path("targetDir").asText()));
                case JobType.SYNC -> {
                    long dest = p.path("destMachineId").asLong();
                    profiles.dirs(p.path("profileId").asLong()).forEach(d -> d.mappings().stream()
                            .filter(m -> m.machineId() == dest).forEach(m -> roots.add(new Root(dest, m.path()))));
                }
                case JobType.SYNC_UNDO -> archives.find(j.ownerId(), p.path("archiveId").asLong()).ifPresent(a -> {
                    String destName = a.destMachineName();
                    long destId = machines.findAll(j.ownerId()).stream().filter(m -> m.name().equals(destName))
                            .map(Machine::id).findFirst().orElse(-1L);
                    List<Manifest.Directory> dirs = json.readValue(a.mapping(),
                            new TypeReference<List<Manifest.Directory>>() {
                            });
                    for (Manifest.Directory d : dirs) {
                        roots.add(new Root(destId, d.path()));
                        if (d.mappings() != null) {
                            d.mappings().stream().filter(m -> destName.equals(m.machine()))
                                    .forEach(m -> roots.add(new Root(destId, m.path())));
                        }
                    }
                });
                default -> {
                }
            }
        } catch (RuntimeException e) {
            // parâmetros ilegíveis: não bloqueia por palpite
        }
        return roots;
    }
}
