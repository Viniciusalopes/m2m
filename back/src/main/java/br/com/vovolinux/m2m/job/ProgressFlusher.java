package br.com.vovolinux.m2m.job;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** ~4 eventos por segundo para o navegador; grava o último estado no banco a cada ~2 s e a cada mudança de etapa. */
@Component
public class ProgressFlusher {

    private static final Logger log = LoggerFactory.getLogger(ProgressFlusher.class);
    private static final Duration PERSIST_EVERY = Duration.ofSeconds(2);

    private final RunningJobs running;
    private final ProgressBus bus;
    private final JobRepository repository;
    private final JsonMapper json;
    private final Map<Long, Instant> lastPersist = new ConcurrentHashMap<>();

    public ProgressFlusher(RunningJobs running, ProgressBus bus, JobRepository repository, JsonMapper json) {
        this.running = running;
        this.bus = bus;
        this.repository = repository;
        this.json = json;
    }

    @Scheduled(fixedRate = 250)
    void flush() {
        for (RunningJobs.Running r : running.all()) {
            JobProgress p = r.progress();
            boolean stepChanged = p.consumeStepChanged();
            boolean dirty = p.consumeDirty() || stepChanged;
            if (dirty) {
                Map<String, Object> event = new HashMap<>(p.snapshot());
                event.put("type", "progress");
                event.put("jobId", r.job().id());
                event.put("status", JobStatus.RUNNING);
                bus.publish("job." + r.job().id(), event);
            }
            Instant last = lastPersist.get(r.job().id());
            if (stepChanged || last == null || Duration.between(last, Instant.now()).compareTo(PERSIST_EVERY) >= 0) {
                try {
                    repository.saveProgress(r.job().id(), p.step(), json.writeValueAsString(p.snapshot()));
                    lastPersist.put(r.job().id(), Instant.now());
                } catch (RuntimeException e) {
                    log.debug("Progresso do job {} não gravado: {}", r.job().id(), e.getMessage());
                }
            }
        }
        lastPersist.keySet().removeIf(id -> running.get(id).isEmpty());
    }
}
