package br.com.vovolinux.m2m.profile;

import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Gatilhos diários de backup por perfil, no fuso America/Sao_Paulo; recriados quando o perfil muda. */
@Component
@Order(30)
public class BackupScheduler implements ApplicationRunner {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final ProfileRepository profiles;
    private final JobService jobService;
    private final JobRepository jobs;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public BackupScheduler(ProfileRepository profiles, JobService jobService, JobRepository jobs) {
        this.profiles = profiles;
        this.jobService = jobService;
        this.jobs = jobs;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("m2m-schedule-");
        this.scheduler.initialize();
    }

    @Override
    public void run(ApplicationArguments args) {
        for (long id : profiles.scheduledProfileIds()) {
            reschedule(id);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProfileChanged(ProfileService.ProfileChanged event) {
        if (event.deleted()) {
            cancel(event.profileId());
        } else {
            reschedule(event.profileId());
        }
    }

    /** Cria (ou recria) o gatilho do perfil conforme o agendamento salvo; sem agendamento ativo, só cancela. */
    public void reschedule(long profileId) {
        cancel(profileId);
        Optional<ProfileRepository.ScheduleRow> s = profiles.backupSchedule(profileId);
        if (s.isEmpty() || !s.get().enabled()) {
            return;
        }
        String cron = "0 " + s.get().time().getMinute() + " " + s.get().time().getHour() + " * * *";
        futures.put(profileId, scheduler.schedule(() -> fire(profileId), new CronTrigger(cron, ZONE)));
        log.info("Backup do perfil {} agendado para {} (America/Sao_Paulo)", profileId, s.get().time());
    }

    public boolean isScheduled(long profileId) {
        return futures.containsKey(profileId);
    }

    private void cancel(long profileId) {
        ScheduledFuture<?> f = futures.remove(profileId);
        if (f != null) {
            f.cancel(false);
        }
    }

    /** Disparo do horário: entra na fila (nunca é recusado) e espera a vez; não duplica um backup já na fila. */
    void fire(long profileId) {
        try {
            if (jobs.queuedExists(JobType.BACKUP, "SCHEDULED", profileId)) {
                return;
            }
            var row = profiles.findById(profileId);
            if (row.isEmpty()) {
                return;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("profileId", profileId);
            params.put("complement", null);
            jobService.enqueue(row.get().ownerId(), JobType.BACKUP, "SCHEDULED", profileId, row.get().name(), params);
        } catch (RuntimeException e) {
            log.error("Falha ao enfileirar o backup agendado do perfil {}", profileId, e);
        }
    }

    public TaskScheduler taskScheduler() {
        return scheduler;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
