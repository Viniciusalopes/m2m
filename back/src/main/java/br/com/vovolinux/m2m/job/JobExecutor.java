package br.com.vovolinux.m2m.job;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.notification.NotificationService;
import br.com.vovolinux.m2m.notification.SpaceMonitor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor único (prefetch 1) da fila {@code m2m.jobs.run}: executa um job por vez (D9). Mensagens de jobs que já
 * não estão QUEUED são ignoradas, o que torna inofensiva qualquer republicação.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository repository;
    private final Map<String, JobHandler> handlers;
    private final RunningJobs running;
    private final ProgressBus bus;
    private final JsonMapper json;
    private final JobService service;
    private final AuditService audit;
    private final NotificationService notifications;
    private final SpaceMonitor spaceMonitor;

    public JobExecutor(JobRepository repository, List<JobHandler> handlers, RunningJobs running, ProgressBus bus,
            JsonMapper json, JobService service, AuditService audit, NotificationService notifications,
            SpaceMonitor spaceMonitor) {
        this.repository = repository;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.running = running;
        this.bus = bus;
        this.json = json;
        this.service = service;
        this.audit = audit;
        this.notifications = notifications;
        this.spaceMonitor = spaceMonitor;
    }

    public JobHandler handlerFor(String type) {
        return handlers.get(type);
    }

    @RabbitListener(queues = RabbitTopology.JOBS_RUN, concurrency = "1")
    public void onMessage(String body) {
        long id;
        try {
            id = Long.parseLong(body.trim());
        } catch (NumberFormatException e) {
            log.warn("Mensagem inválida na fila de jobs: {}", body);
            return;
        }
        Optional<Job> claimed = repository.claim(id);
        if (claimed.isEmpty()) {
            log.debug("Job {} ignorado: já não está na fila", id);
            return;
        }
        execute(claimed.get());
    }

    void execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        CancellationToken token = new CancellationToken();
        JobProgress progress = new JobProgress();
        if (handler == null) {
            finish(job, JobStatus.FAILED, progress, Map.of("error", Map.of("codigo", "job.tipo-sem-handler",
                    "parametros", Map.of("tipo", job.type()))));
            return;
        }
        running.register(new RunningJobs.Running(job, token, progress, handler));
        publishProgress(job, JobStatus.RUNNING, progress);
        JobContext ctx = new JobContext(job, json.readTree(job.params()), token, progress, service);
        String status;
        Map<String, Object> result = new HashMap<>();
        try {
            handler.run(ctx);
            status = ctx.finalStatus();
            result.putAll(ctx.result());
        } catch (CancellationException e) {
            status = JobStatus.INTERRUPTED;
            result.putAll(ctx.result());
            result.put("reason", "USER");
        } catch (Throwable e) {
            result.putAll(ctx.result());
            if (token.isCancelled()) {
                // O fechamento forçado das conexões faz leituras e gravações falharem: é o cancelamento em ação.
                status = JobStatus.INTERRUPTED;
                result.put("reason", "USER");
            } else {
                status = JobStatus.FAILED;
                result.put("error", errorOf(e));
                log.warn("Job {} ({}) falhou", job.id(), job.type(), e);
            }
        }
        finish(job, status, progress, result);
    }

    private static Map<String, Object> errorOf(Throwable e) {
        Map<String, Object> m = new HashMap<>();
        if (e instanceof GatewayException g) {
            m.put("codigo", g.codigo());
            m.put("parametros", g.parametros());
        } else if (e instanceof JobFailure f) {
            m.put("codigo", f.codigo());
            m.put("parametros", f.parametros());
        } else {
            m.put("codigo", "job.falha-inesperada");
            m.put("parametros", Map.of("detalhe", String.valueOf(e.getMessage())));
        }
        return m;
    }

    private void finish(Job job, String status, JobProgress progress, Map<String, Object> result) {
        running.unregister(job.id());
        try {
            repository.finish(job.id(), status, json.writeValueAsString(progress.snapshot()),
                    json.writeValueAsString(result));
        } catch (RuntimeException e) {
            log.error("Não foi possível gravar o fim do job {}", job.id(), e);
        }
        audit.record(job.ownerId(), null, null, "JOB_" + status, job.type() + "#" + job.id(),
                status.equals(JobStatus.FAILED) ? AuditService.FALHA : AuditService.SUCESSO,
                Map.of("tipo", job.type(), "perfil", String.valueOf(job.profileName())));
        Map<String, Object> event = new HashMap<>();
        event.put("type", "done");
        event.put("jobId", job.id());
        event.put("status", status);
        event.put("result", result);
        event.putAll(progress.snapshot());
        bus.publish("job." + job.id(), event);
        notifyOutcome(job, status, result);
        spaceMonitor.checkAsync(); // a cada job: confere o espaço dos armazenamentos
    }

    /** Falhas e abortos de jobs agendados/do sistema viram notificação; nunca afetam o job. */
    private void notifyOutcome(Job job, String status, Map<String, Object> result) {
        try {
            boolean automatic = !"MANUAL".equals(job.trigger());
            Map<String, Object> p = new HashMap<>();
            p.put("tipo", job.type());
            p.put("perfil", job.profileName() == null ? "—" : job.profileName());
            p.put("horario", java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
            p.put("jobId", job.id());
            p.put("motivo", reasonOf(result));
            if (JobType.DB_BACKUP.equals(job.type()) && JobStatus.FAILED.equals(status)) {
                notifications.notifyAdmins("DB_BACKUP_FAILED", NotificationService.ERROR, p);
            } else if (automatic && JobStatus.FAILED.equals(status)) {
                notifications.notify(job.ownerId(), "JOB_FAILED", NotificationService.ERROR, p, "job", job.id());
            } else if (automatic && JobStatus.INTERRUPTED.equals(status)) {
                notifications.notify(job.ownerId(), "JOB_INTERRUPTED", NotificationService.WARN, p, "job", job.id());
            }
        } catch (RuntimeException e) {
            log.warn("Notificação do job {} não enviada: {}", job.id(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String reasonOf(Map<String, Object> result) {
        Object err = result.get("error");
        if (err instanceof Map<?, ?> m) {
            return m.get("codigo") + (m.get("parametros") == null ? "" : " " + m.get("parametros"));
        }
        return String.valueOf(result.getOrDefault("reason", ""));
    }

    void publishProgress(Job job, String status, JobProgress progress) {
        Map<String, Object> event = new HashMap<>(progress.snapshot());
        event.put("type", "progress");
        event.put("jobId", job.id());
        event.put("status", status);
        bus.publish("job." + job.id(), event);
    }
}
