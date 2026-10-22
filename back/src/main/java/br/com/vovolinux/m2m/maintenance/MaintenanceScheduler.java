package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobType;
import jakarta.annotation.PreDestroy;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Gatilhos da verificação periódica dos backups e do backup diário do banco (America/Sao_Paulo). */
@Component
@Order(32)
public class MaintenanceScheduler implements ApplicationRunner {

    /** Publicado quando as configurações de manutenção mudam. */
    public record SettingsChanged() {
    }

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final MaintenanceSettings settings;
    private final JobService jobService;
    private final JobRepository jobs;
    private final JdbcClient jdbc;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public MaintenanceScheduler(MaintenanceSettings settings, JobService jobService, JobRepository jobs,
            JdbcClient jdbc) {
        this.settings = settings;
        this.jobService = jobService;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("m2m-maintenance-");
        this.scheduler.initialize();
    }

    @Override
    public void run(ApplicationArguments args) {
        reschedule();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onChanged(SettingsChanged e) {
        reschedule();
    }

    public synchronized void reschedule() {
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        var v = settings.verify();
        if (v.enabled()) {
            futures.put(JobType.ARCHIVE_VERIFY, scheduler.schedule(() -> fire(JobType.ARCHIVE_VERIFY),
                    new CronTrigger(v.cron(), ZONE)));
        }
        var d = settings.dbBackup();
        if (d.enabled()) {
            LocalTime t = LocalTime.parse(d.time());
            futures.put(JobType.DB_BACKUP, scheduler.schedule(() -> fire(JobType.DB_BACKUP),
                    new CronTrigger("0 " + t.getMinute() + " " + t.getHour() + " * * *", ZONE)));
        }
    }

    public boolean isScheduled(String type) {
        return futures.containsKey(type);
    }

    void fire(String type) {
        try {
            if (jobs.queuedExists(type, "SCHEDULED", null)) {
                return;
            }
            jdbc.sql("select id from app_user where role = 'ADMIN' and enabled order by id limit 1")
                    .query(Long.class).optional().ifPresent(
                            owner -> jobService.enqueue(owner, type, "SCHEDULED", null, null, Map.of()));
        } catch (RuntimeException e) {
            log.error("Falha ao enfileirar o job agendado {}", type, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
