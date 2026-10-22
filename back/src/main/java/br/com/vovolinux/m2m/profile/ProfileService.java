package br.com.vovolinux.m2m.profile;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.profile.ProfileDtos.Defaults;
import br.com.vovolinux.m2m.profile.ProfileDtos.Dir;
import br.com.vovolinux.m2m.profile.ProfileDtos.DirView;
import br.com.vovolinux.m2m.profile.ProfileDtos.Mapping;
import br.com.vovolinux.m2m.profile.ProfileDtos.ProfileRequest;
import br.com.vovolinux.m2m.profile.ProfileDtos.ProfileView;
import br.com.vovolinux.m2m.profile.ProfileDtos.Schedule;
import br.com.vovolinux.m2m.profile.ProfileDtos.Storage;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    public static final String DEFAULT_STORAGE_DIR = "/home/backup";
    public static final double DEFAULT_THRESHOLD = 0.50;
    private static final Pattern COMPLEMENT = Pattern.compile("[A-Za-z0-9_-]*");

    /** Publicado após salvar/excluir, para recriar os gatilhos de agendamento. */
    public record ProfileChanged(long profileId, boolean deleted) {
    }

    private final ProfileRepository repository;
    private final MachineRepository machines;
    private final StorageProbe probe;
    private final LocalProperties local;
    private final AuditService audit;
    private final JobRepository jobs;
    private final JobService jobService;
    private final BackupArchiveRepository archives;
    private final ArchiveFiles archiveFiles;
    private final ApplicationEventPublisher events;

    public ProfileService(ProfileRepository repository, MachineRepository machines, StorageProbe probe,
            LocalProperties local, AuditService audit, JobRepository jobs, JobService jobService,
            BackupArchiveRepository archives, ArchiveFiles archiveFiles, ApplicationEventPublisher events) {
        this.repository = repository;
        this.machines = machines;
        this.probe = probe;
        this.local = local;
        this.audit = audit;
        this.jobs = jobs;
        this.jobService = jobService;
        this.archives = archives;
        this.archiveFiles = archiveFiles;
        this.events = events;
    }

    public Defaults defaults(long ownerId) {
        Long localId = machines.findAll(ownerId).stream().filter(m -> m.protocol() == Protocol.LOCAL).map(Machine::id)
                .findFirst().orElse(null);
        return new Defaults(localId, DEFAULT_STORAGE_DIR, DEFAULT_THRESHOLD);
    }

    public List<ProfileView> list(long ownerId) {
        return repository.findAll(ownerId).stream().map(this::view).toList();
    }

    public ProfileRepository.Row require(long ownerId, long id) {
        return repository.find(ownerId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "perfil.nao-encontrado"));
    }

    public ProfileView get(long ownerId, long id) {
        return view(require(ownerId, id));
    }

    @Transactional
    public ProfileView create(long ownerId, ProfileRequest req) {
        Validated v = validate(ownerId, null, req);
        long id;
        try {
            id = repository.insert(ownerId, v.name, v.complement, v.source.id(), v.storage.id(), v.storageDir,
                    v.threshold, v.encryption);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "perfil.nome-duplicado", Map.of("nome", v.name));
        }
        persistChildren(id, v, req);
        audit.record("PROFILE_CREATE", v.name, AuditService.SUCESSO, Map.of("diretorios", v.dirs.size()));
        events.publishEvent(new ProfileChanged(id, false));
        return get(ownerId, id);
    }

    @Transactional
    public ProfileView update(long ownerId, long id, ProfileRequest req) {
        require(ownerId, id);
        Validated v = validate(ownerId, id, req);
        try {
            repository.update(ownerId, id, v.name, v.complement, v.source.id(), v.storage.id(), v.storageDir,
                    v.threshold, v.encryption);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "perfil.nome-duplicado", Map.of("nome", v.name));
        }
        persistChildren(id, v, req);
        audit.record("PROFILE_UPDATE", v.name, AuditService.SUCESSO, Map.of());
        events.publishEvent(new ProfileChanged(id, false));
        return get(ownerId, id);
    }

    /**
     * Exclui o perfil e seus agendamentos. Os backups são mantidos como órfãos ou excluídos junto com os arquivos.
     * Recusado enquanto houver job do perfil em execução ou na fila.
     */
    @Transactional
    public void delete(long ownerId, long id, boolean keepBackups) {
        ProfileRepository.Row p = require(ownerId, id);
        List<Job> ativos = jobs.activeOfProfile(id);
        if (!ativos.isEmpty()) {
            Job a = ativos.get(0);
            throw new ApiException(HttpStatus.CONFLICT, "perfil.job-ativo",
                    Map.of("jobId", a.id(), "tipo", a.type(), "status", a.status()));
        }
        int removidos = 0;
        if (!keepBackups) {
            for (BackupArchive a : archives.byProfile(ownerId, id)) {
                try {
                    archiveFiles.delete(a);
                } catch (IOException e) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "perfil.falha-ao-excluir-backups",
                            Map.of("arquivo", a.fileName(), "detalhe", String.valueOf(e.getMessage())));
                }
                archives.delete(a.id());
                removidos++;
            }
        } else {
            archives.orphanize(id);
        }
        repository.delete(ownerId, id);
        audit.record("PROFILE_DELETE", p.name(), AuditService.SUCESSO,
                Map.of("backupsMantidos", keepBackups, "backupsExcluidos", removidos));
        events.publishEvent(new ProfileChanged(id, true));
    }

    /** Backup manual: enfileira o job (recusado se já houver job em execução ou na fila). */
    public Job startBackup(long ownerId, long id, String complement) {
        ProfileRepository.Row p = require(ownerId, id);
        String c = complement == null || complement.isBlank() ? null : complement.trim();
        if (c != null && !COMPLEMENT.matcher(c).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.complemento-invalido");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("profileId", id);
        params.put("complement", c);
        Job job = jobService.enqueue(ownerId, JobType.BACKUP, "MANUAL", id, p.name(), params);
        audit.record("BACKUP_START", p.name(), AuditService.SUCESSO, Map.of("jobId", job.id(), "gatilho", "MANUAL"));
        return job;
    }

    // ---- validação ----

    private record Validated(String name, String complement, Machine source, Machine storage, String storageDir,
            List<Dir> dirs, double threshold, boolean encryption, LocalTime time, boolean scheduleEnabled,
            boolean hasSchedule) {
    }

    private Validated validate(long ownerId, Long id, ProfileRequest req) {
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.nome-obrigatorio");
        }
        if (repository.nameExists(ownerId, name, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "perfil.nome-duplicado", Map.of("nome", name));
        }
        String complement = req.complement() == null || req.complement().isBlank() ? null : req.complement().trim();
        if (complement != null && !COMPLEMENT.matcher(complement).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.complemento-invalido");
        }
        double threshold = req.suspectThreshold() == null ? DEFAULT_THRESHOLD : req.suspectThreshold();
        Machine source = machines.find(ownerId, req.sourceMachineId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.origem-inexistente"));
        Machine storage = machines.find(ownerId, req.storage().machineId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.armazenamento-inexistente"));

        List<Dir> dirs = normalizeDirs(ownerId, source, req.directories());
        String storageDir = normalizePath(req.storage().path(), "perfil.armazenamento-invalido");
        if (storage.id() == source.id()) {
            for (Dir d : dirs) {
                if (Paths.sameOrInside(d.path(), storageDir)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.armazenamento-dentro-da-origem",
                            Map.of("diretorio", d.path(), "armazenamento", storageDir));
                }
            }
        }
        try {
            if (!probe.canWrite(storage, storageDir)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.armazenamento-sem-permissao",
                        Map.of("maquina", storage.name(), "diretorio", storageDir));
            }
        } catch (IOException e) {
            throw gatewayFailure(e, storage);
        }

        LocalTime time = null;
        boolean enabled = false;
        boolean hasSchedule = req.backupSchedule() != null;
        if (hasSchedule) {
            try {
                time = LocalTime.parse(req.backupSchedule().time());
            } catch (DateTimeParseException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.horario-invalido");
            }
            enabled = req.backupSchedule().enabled();
        }
        return new Validated(name, complement, source, storage, storageDir, dirs, threshold,
                Boolean.TRUE.equals(req.encryptionEnabled()), time, enabled, hasSchedule);
    }

    private List<Dir> normalizeDirs(long ownerId, Machine source, List<Dir> requested) {
        List<Dir> out = new ArrayList<>();
        for (Dir d : requested) {
            String path = normalizePath(d.path(), "perfil.diretorio-invalido");
            if (isRoot(source, path)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorio-raiz", Map.of("diretorio", path));
            }
            for (Dir other : out) {
                if (Paths.overlap(other.path(), path)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorios-sobrepostos",
                            Map.of("diretorio", path, "conflito", other.path()));
                }
            }
            try {
                if (!probe.directoryExists(source, path)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.diretorio-inexistente",
                            Map.of("diretorio", path, "maquina", source.name()));
                }
            } catch (IOException e) {
                throw gatewayFailure(e, source);
            }
            out.add(new Dir(path, normalizeMappings(ownerId, source, path, d.mappings())));
        }
        // Os destinos mapeados numa mesma máquina também não podem se sobrepor entre si.
        Map<Long, List<String>> porMaquina = new HashMap<>();
        for (Dir d : out) {
            for (Mapping m : d.mappings()) {
                List<String> usados = porMaquina.computeIfAbsent(m.machineId(), k -> new ArrayList<>());
                for (String u : usados) {
                    if (Paths.overlap(u, m.path())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.mapeamento-sobreposto",
                                Map.of("caminho", m.path(), "conflito", u));
                    }
                }
                usados.add(m.path());
            }
        }
        return out;
    }

    private List<Mapping> normalizeMappings(long ownerId, Machine source, String dir, List<Mapping> requested) {
        if (requested == null) {
            return List.of();
        }
        List<Mapping> out = new ArrayList<>();
        Set<Long> vistas = new HashSet<>();
        for (Mapping m : requested) {
            if (m.machineId() == source.id()) {
                continue; // a origem não precisa de mapeamento para si mesma
            }
            machines.find(ownerId, m.machineId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente"));
            if (!vistas.add(m.machineId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.mapeamento-duplicado", Map.of("diretorio", dir));
            }
            out.add(new Mapping(m.machineId(), normalizePath(m.path(), "perfil.mapeamento-invalido")));
        }
        return out;
    }

    private boolean isRoot(Machine machine, String path) {
        if (path.equals("/")) {
            return true;
        }
        if (machine.protocol() == Protocol.LOCAL) {
            return local.roots().stream().map(Paths::normalize).anyMatch(path::equals);
        }
        return false;
    }

    private static String normalizePath(String path, String errorCode) {
        try {
            return Paths.normalize(path.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, Map.of("caminho", path));
        }
    }

    private static ApiException gatewayFailure(IOException e, Machine m) {
        if (e instanceof br.com.vovolinux.m2m.files.GatewayException g) {
            Map<String, Object> p = new HashMap<>(g.parametros());
            p.put("maquina", m.name());
            return new ApiException(g.codigo().startsWith("identidade.") ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    g.codigo(), p);
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "conexao.falha",
                Map.of("host", m.host(), "maquina", m.name(), "detalhe", String.valueOf(e.getMessage())));
    }

    private void persistChildren(long id, Validated v, ProfileRequest req) {
        repository.replaceDirs(id, v.dirs);
        if (v.hasSchedule) {
            repository.saveBackupSchedule(id, v.time, v.scheduleEnabled);
        } else {
            repository.deleteBackupSchedule(id);
        }
    }

    // ---- visão ----

    ProfileView view(ProfileRepository.Row p) {
        String sourceName = machines.find(p.ownerId(), p.sourceMachineId()).map(Machine::name).orElse("?");
        String storageName = machines.find(p.ownerId(), p.storageMachineId()).map(Machine::name).orElse("?");
        List<DirView> dirs = repository.dirs(p.id()).stream().map(d -> new DirView(d.id(), d.position(), d.path(),
                d.mappings().stream().map(m -> new Mapping(m.machineId(), m.path())).toList())).toList();
        Schedule schedule = repository.backupSchedule(p.id())
                .map(s -> new Schedule(s.time().toString(), s.enabled())).orElse(null);
        return new ProfileView(p.id(), p.name(), p.complement(), p.sourceMachineId(), sourceName, dirs,
                new Storage(p.storageMachineId(), p.storageDir()), storageName, p.suspectThreshold(),
                p.encryptionEnabled(), schedule, null, p.createdAt());
    }
}
