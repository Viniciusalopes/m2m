package br.com.vovolinux.m2m.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Na inicialização: jobs RUNNING viram INTERRUPTED (com limpeza de parciais e temporários) e jobs QUEUED sem
 * publicação pendente são republicados, sem duplicar (D9, D10).
 */
@Component
@Order(20)
public class JobRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRecovery.class);

    private final JobRepository repository;
    private final JobExecutor executor;
    private final OutboxPublisher outbox;
    private final JsonMapper json;
    private final Path tmpDir;

    public JobRecovery(JobRepository repository, JobExecutor executor, OutboxPublisher outbox, JsonMapper json,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir) {
        this.repository = repository;
        this.executor = executor;
        this.outbox = outbox;
        this.json = json;
        this.tmpDir = Path.of(tmpDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Job job : repository.markRunningInterrupted()) {
            log.warn("Job {} ({}) estava em execução quando a aplicação parou: marcado como interrompido", job.id(),
                    job.type());
            JobHandler handler = executor.handlerFor(job.type());
            if (handler != null) {
                try {
                    handler.recover(job);
                } catch (RuntimeException e) {
                    log.warn("Falha ao limpar o job {}: {}", job.id(), e.getMessage());
                }
            }
            deleteTempDir(job.id());
        }
        List<Job> semPublicacao = repository.queuedWithoutPendingPublication();
        for (Job job : semPublicacao) {
            repository.insertOutbox("JOB", job.id(), RabbitTopology.JOBS_EXCHANGE, RabbitTopology.RUN_KEY, "{}");
        }
        if (!semPublicacao.isEmpty()) {
            log.info("{} job(s) na fila republicado(s)", semPublicacao.size());
        }
        outbox.kick();
    }

    private void deleteTempDir(long jobId) {
        Path dir = tmpDir.resolve("job-" + jobId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Não foi possível limpar {}: {}", dir, e.getMessage());
        }
    }
}
