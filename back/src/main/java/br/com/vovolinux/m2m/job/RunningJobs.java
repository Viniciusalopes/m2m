package br.com.vovolinux.m2m.job;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Jobs em execução neste processo, com cancelamento e progresso. */
@Component
public class RunningJobs {

    public record Running(Job job, CancellationToken token, JobProgress progress, JobHandler handler) {
    }

    private final Map<Long, Running> running = new ConcurrentHashMap<>();

    void register(Running r) {
        running.put(r.job().id(), r);
    }

    void unregister(long id) {
        running.remove(id);
    }

    public Optional<Running> get(long id) {
        return Optional.ofNullable(running.get(id));
    }

    public Collection<Running> all() {
        return running.values();
    }
}
