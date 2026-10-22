package br.com.vovolinux.m2m.job;

import br.com.vovolinux.m2m.security.CurrentUser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobView(long id, String type, String status, String step, JsonNode progress, String trigger,
            Long profileId, String profileName, Instant createdAt, Instant startedAt, Instant finishedAt,
            JsonNode result) {
    }

    public record ActiveJobs(JobView running, List<JobView> queued) {
    }

    public record Page(List<JobView> items, long total) {
    }

    public record MessageView(long id, Instant at, String level, String code, JsonNode params) {
    }

    private final JobService service;
    private final JobRepository repository;
    private final CurrentUser currentUser;
    private final SseHub hub;
    private final JsonMapper json;

    public JobController(JobService service, JobRepository repository, CurrentUser currentUser, SseHub hub,
            JsonMapper json) {
        this.service = service;
        this.repository = repository;
        this.currentUser = currentUser;
        this.hub = hub;
        this.json = json;
    }

    public JobView view(Job j) {
        return new JobView(j.id(), j.type(), j.status(), j.step(), parse(j.progress()), j.trigger(), j.profileId(),
                j.profileName(), j.createdAt(), j.startedAt(), j.finishedAt(), parse(j.result()));
    }

    private JsonNode parse(String s) {
        return s == null ? null : json.readTree(s);
    }

    private long owner() {
        return currentUser.require().id();
    }

    /** Job em execução e fila: usado para reabrir a tela de acompanhamento. */
    @GetMapping("/active")
    public ActiveJobs active() {
        long owner = owner();
        List<Job> ativos = service.active().stream().filter(j -> j.ownerId() == owner).toList();
        JobView run = ativos.stream().filter(j -> j.status().equals(JobStatus.RUNNING)).findFirst().map(this::view)
                .orElse(null);
        return new ActiveJobs(run, ativos.stream().filter(j -> j.status().equals(JobStatus.QUEUED)).map(this::view)
                .toList());
    }

    @GetMapping
    public Page history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        long owner = owner();
        int limit = Math.min(Math.max(size, 1), 200);
        return new Page(repository.history(owner, limit, Math.max(page, 0) * limit).stream().map(this::view).toList(),
                repository.countHistory(owner));
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable long id) {
        return view(service.require(owner(), id));
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable long id) {
        service.require(owner(), id);
        return repository.messages(id).stream()
                .map(m -> new MessageView(m.id(), m.at(), m.level(), m.code(), parse(m.params()))).toList();
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable long id) {
        return view(service.cancel(owner(), id));
    }

    /** Progresso em tempo real (Server-Sent Events); envia o estado atual ao abrir e depois cada atualização. */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        Job job = service.require(owner(), id);
        SseEmitter emitter = hub.subscribe("job." + id);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("type", JobStatus.isFinal(job.status()) ? "done" : "progress");
        snapshot.put("jobId", job.id());
        snapshot.put("status", job.status());
        if (job.progress() != null) {
            snapshot.putAll(json.convertValue(json.readTree(job.progress()), Map.class));
        }
        if (job.result() != null) {
            snapshot.put("result", json.readTree(job.result()));
        }
        hub.send(emitter, "progress", json.writeValueAsString(snapshot));
        return emitter;
    }
}
