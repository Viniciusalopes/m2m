package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import br.com.vovolinux.m2m.profile.ProfileService;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Gatilhos diários das sincronizações agendadas (America/Sao_Paulo); recriados quando os agendamentos mudam. */
@Component
@Order(31)
public class SyncScheduler implements ApplicationRunner {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncScheduleRepository schedules;
    private final ProfileRepository profiles;
    private final JobService jobService;
    private final JobRepository jobs;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public SyncScheduler(SyncScheduleRepository schedules, ProfileRepository profiles, JobService jobService,
            JobRepository jobs) {
        this.schedules = schedules;
        this.profiles = profiles;
        this.jobService = jobService;
        this.jobs = jobs;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(2);
        this.scheduler.setThreadNamePrefix("m2m-sync-schedule-");
        this.scheduler.initialize();
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SyncScheduleRepository.Row s : schedules.enabledAll()) {
            schedule(s);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSchedulesChanged(SyncService.SyncScheduleChanged e) {
        reschedule(e.profileId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProfileChanged(ProfileService.ProfileChanged e) {
        reschedule(e.profileId());
    }

    public void reschedule(long profileId) {
        futures.entrySet().removeIf(entry -> {
            var s = schedules.find(entry.getKey());
            if (s.isEmpty() || s.get().profileId() == profileId) {
                entry.getValue().cancel(false);
                return true;
            }
            return false;
        });
        for (SyncScheduleRepository.Row s : schedules.byProfile(profileId)) {
            if (s.enabled()) {
                schedule(s);
            }
        }
    }

    private void schedule(SyncScheduleRepository.Row s) {
        String cron = "0 " + s.time().getMinute() + " " + s.time().getHour() + " * * *";
        futures.put(s.id(), scheduler.schedule(() -> fire(s.id()), new CronTrigger(cron, ZONE)));
    }

    public boolean isScheduled(long scheduleId) {
        return futures.containsKey(scheduleId);
    }

    /** Disparo do horário: entra na fila como agendado (nunca é recusado). */
    void fire(long scheduleId) {
        try {
            var s = schedules.find(scheduleId);
            if (s.isEmpty() || !s.get().enabled()) {
                return;
            }
            var p = profiles.findById(s.get().profileId());
            if (p.isEmpty()) {
                return;
            }
            if (jobs.queuedExists(JobType.SYNC, "SCHEDULED", p.get().id())) {
                return;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("profileId", p.get().id());
            params.put("destMachineId", s.get().destMachineId());
            params.put("scheduleId", s.get().id());
            params.put("maxDeletionsPercent", s.get().maxDeletionsPercent());
            params.put("maxDeletionsAbs", s.get().maxDeletionsAbs());
            jobService.enqueue(p.get().ownerId(), JobType.SYNC, "SCHEDULED", p.get().id(), p.get().name(), params);
        } catch (RuntimeException e) {
            log.error("Falha ao enfileirar a sincronização agendada {}", scheduleId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
