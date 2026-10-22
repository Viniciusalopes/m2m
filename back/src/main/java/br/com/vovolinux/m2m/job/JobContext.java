package br.com.vovolinux.m2m.job;

import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** O que um {@link JobHandler} recebe ao executar: parâmetros, cancelamento, progresso e resultado. */
public class JobContext {

    private final Job job;
    private final JsonNode params;
    private final CancellationToken token;
    private final JobProgress progress;
    private final JobService service;
    private final Map<String, Object> result = new HashMap<>();
    private String finalStatus = JobStatus.COMPLETED;

    JobContext(Job job, JsonNode params, CancellationToken token, JobProgress progress, JobService service) {
        this.job = job;
        this.params = params;
        this.token = token;
        this.progress = progress;
        this.service = service;
    }

    public long id() {
        return job.id();
    }

    public long ownerId() {
        return job.ownerId();
    }

    public Job job() {
        return job;
    }

    public JsonNode params() {
        return params;
    }

    public CancellationToken token() {
        return token;
    }

    public JobProgress progress() {
        return progress;
    }

    /** Registra uma mensagem (aviso, informação) visível no acompanhamento e no histórico. */
    public void message(String level, String code, Map<String, Object> params) {
        service.addMessage(job.id(), level, code, params);
        if (level.equals("WARN")) {
            progress.warning();
        }
    }

    /** Dados que vão para {@code job.result}. */
    public Map<String, Object> result() {
        return result;
    }

    /** O job termina com avisos (em vez de COMPLETED). */
    public void completeWithWarnings() {
        this.finalStatus = JobStatus.COMPLETED_WITH_WARNINGS;
    }

    /** O handler decide o estado final (ex.: AWAITING_CONFIRMATION). */
    public void finishAs(String status) {
        this.finalStatus = status;
    }

    String finalStatus() {
        return finalStatus;
    }
}
