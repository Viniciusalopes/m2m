package br.com.vovolinux.m2m.dashboard;

import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.notification.NotificationController;
import br.com.vovolinux.m2m.notification.NotificationRepository;
import br.com.vovolinux.m2m.notification.SpaceMonitor;
import br.com.vovolinux.m2m.profile.BackupScheduler;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Painel inicial (D27): tudo que o usuário precisa ver ao entrar, num só endpoint. */
@Service
public class DashboardService {

    public record LastBackup(long id, String status, Instant at, long archiveBytes, long fileCount, String fileName) {
    }

    public record LastSync(String destination, String status, Instant at) {
    }

    public record ProfileStatus(long id, String name, String sourceMachine, String storage, LastBackup lastBackup,
            List<LastSync> lastSyncs, Instant nextBackupAt, boolean scheduled) {
    }

    public record JobBrief(long id, String type, String status, String profileName, String trigger, Instant createdAt) {
    }

    public record Attention(long id, String fileName, String profileName, String status, Instant createdAt) {
    }

    public record StorageUsage(String machine, String dir, Long freeBytes, Long totalBytes, Double freePercent,
            String state) {
    }

    public record Dashboard(JobBrief running, List<JobBrief> queue, List<ProfileStatus> profiles, long unread,
            List<NotificationController.NotificationView> alerts, List<Attention> attention,
            List<JobBrief> awaitingConfirmation, List<StorageUsage> storage) {
    }

    private final ProfileRepository profiles;
    private final BackupArchiveRepository archives;
    private final JobRepository jobs;
    private final NotificationRepository notifications;
    private final BackupScheduler scheduler;
    private final SpaceMonitor space;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final br.com.vovolinux.m2m.machine.MachineRepository machines;

    public DashboardService(ProfileRepository profiles, BackupArchiveRepository archives, JobRepository jobs,
            NotificationRepository notifications, BackupScheduler scheduler, SpaceMonitor space, JdbcClient jdbc,
            JsonMapper json, br.com.vovolinux.m2m.machine.MachineRepository machines) {
        this.profiles = profiles;
        this.archives = archives;
        this.jobs = jobs;
        this.notifications = notifications;
        this.scheduler = scheduler;
        this.space = space;
        this.jdbc = jdbc;
        this.json = json;
        this.machines = machines;
    }

    private static JobBrief brief(Job j) {
        return new JobBrief(j.id(), j.type(), j.status(), j.profileName(), j.trigger(), j.createdAt());
    }

    public Dashboard build(long ownerId) {
        List<Job> ativos = jobs.active().stream().filter(j -> j.ownerId() == ownerId).toList();
        JobBrief running = ativos.stream().filter(j -> j.status().equals(JobStatus.RUNNING)).findFirst()
                .map(DashboardService::brief).orElse(null);
        List<JobBrief> queue = ativos.stream().filter(j -> j.status().equals(JobStatus.QUEUED))
                .map(DashboardService::brief).toList();

        List<ProfileStatus> ps = new ArrayList<>();
        for (ProfileRepository.Row p : profiles.findAll(ownerId)) {
            Optional<BackupArchive> last = archives.byProfile(ownerId, p.id()).stream()
                    .filter(a -> a.type().equals("REGULAR")).reduce((a, b) -> b);
            LastBackup lb = last.map(a -> new LastBackup(a.id(), a.status(), a.createdAt(), a.archiveBytes(),
                    a.fileCount(), a.fileName())).orElse(null);
            String source = machines.find(ownerId, p.sourceMachineId()).map(br.com.vovolinux.m2m.machine.Machine::name)
                    .orElse("?");
            String storage = machines.find(ownerId, p.storageMachineId())
                    .map(br.com.vovolinux.m2m.machine.Machine::name).orElse("?") + ":" + p.storageDir();
            Optional<Instant> next = scheduler.nextFire(p.id());
            ps.add(new ProfileStatus(p.id(), p.name(), source, storage, lb, lastSyncs(p.id()), next.orElse(null),
                    next.isPresent()));
        }

        var latest = notifications.list(ownerId, true, 5, 0).stream()
                .map(r -> new NotificationController.NotificationView(r.id(), r.type(), r.severity(), r.messageCode(),
                        json.readTree(r.params()), r.targetType(), r.targetId(), r.read(), r.emailStatus(),
                        r.emailError(), r.createdAt())).toList();

        List<Attention> attention = new ArrayList<>();
        for (String st : List.of(ArchiveStatus.SUSPECT, ArchiveStatus.CORRUPTED, ArchiveStatus.MISSING)) {
            archives.list(ownerId, null, st, null, null, 20, 0).forEach(a -> attention
                    .add(new Attention(a.id(), a.fileName(), a.profileNameSnapshot(), a.status(), a.createdAt())));
        }

        List<JobBrief> awaiting = jdbc.sql("""
                select id, type, status, profile_name, trigger, created_at from job
                 where owner_id = :o and status = 'AWAITING_CONFIRMATION' order by id desc""")
                .param("o", ownerId).query((rs, i) -> new JobBrief(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant())).list();

        List<StorageUsage> storage = new ArrayList<>();
        for (SpaceMonitor.Storage s : space.storages()) {
            if (s.ownerId() != ownerId) {
                continue;
            }
            SpaceMonitor.Measurement m = space.measure(s, true);
            if (m.error() != null) {
                storage.add(new StorageUsage(m.machineName(), s.dir(), null, null, null, "ERROR"));
            } else if (m.space().isEmpty()) {
                storage.add(new StorageUsage(m.machineName(), s.dir(), null, null, null, "UNKNOWN"));
            } else {
                var sp = m.space().get();
                storage.add(new StorageUsage(m.machineName(), s.dir(), sp.freeBytes(), sp.totalBytes(),
                        sp.freePercent(), sp.freePercent() < SpaceMonitor.LIMITE_PERCENTUAL ? "LOW" : "OK"));
            }
        }
        return new Dashboard(running, queue, ps, notifications.countUnread(ownerId), latest, attention, awaiting,
                storage);
    }

    /** Última sincronização por destino (tabela sync_run; preenchida a partir do M2). */
    private List<LastSync> lastSyncs(long profileId) {
        return jdbc.sql("""
                select distinct on (dest_machine_name) dest_machine_name, status, created_at from sync_run
                 where profile_id = :p order by dest_machine_name, id desc""").param("p", profileId)
                .query((rs, i) -> new LastSync(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()))
                .list();
    }
}
