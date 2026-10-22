package br.com.vovolinux.m2m.job;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import br.com.vovolinux.m2m.files.GatewayPool;

@Service
public class JobService {

    private final JobRepository repository;
    private final JsonMapper json;
    private final RunningJobs running;
    private final GatewayPool pool;
    private final AuditService audit;
    private final ProgressBus bus;
    private final OutboxPublisher outbox;

    public JobService(JobRepository repository, JsonMapper json, RunningJobs running, GatewayPool pool,
            AuditService audit, ProgressBus bus, OutboxPublisher outbox) {
        this.repository = repository;
        this.json = json;
        this.running = running;
        this.pool = pool;
        this.audit = audit;
        this.bus = bus;
        this.outbox = outbox;
    }

    /**
     * Cria um job na fila. Pedido manual com algum job em execução ou na fila é recusado (409); jobs agendados sempre
     * entram na fila. A criação e a publicação pendente (outbox) acontecem na mesma transação (D9).
     */
    @Transactional
    public Job enqueue(long ownerId, String type, String trigger, Long profileId, String profileName,
            Map<String, Object> params) {
        repository.lockCreation();
        if ("MANUAL".equals(trigger)) {
            List<Job> ativos = repository.active();
            if (!ativos.isEmpty()) {
                Job a = ativos.get(0);
                Map<String, Object> p = new HashMap<>();
                p.put("jobId", a.id());
                p.put("tipo", a.type());
                p.put("status", a.status());
                p.put("perfil", a.profileName());
                throw new ApiException(HttpStatus.CONFLICT, "job.em-andamento", p);
            }
        }
        long id = repository.insert(ownerId, type, trigger, profileId, profileName, json.writeValueAsString(params));
        repository.insertOutbox("JOB", id, RabbitTopology.JOBS_EXCHANGE, RabbitTopology.RUN_KEY, "{}");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outbox.kick();
                }
            });
        }
        return repository.findAny(id).orElseThrow();
    }

    public Job require(long ownerId, long id) {
        return repository.find(ownerId, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "job.nao-encontrado"));
    }

    public List<Job> active() {
        return repository.active();
    }

    public void addMessage(long jobId, String level, String code, Map<String, Object> params) {
        repository.addMessage(jobId, level, code, json.writeValueAsString(params));
    }

    /**
     * Botão de emergência: sinaliza o cancelamento e fecha à força as conexões das máquinas envolvidas, sem esperar
     * o fim do arquivo atual (D10). Job ainda na fila é apenas cancelado.
     */
    public Job cancel(long ownerId, long id) {
        Job job = require(ownerId, id);
        if (job.status().equals(JobStatus.QUEUED)) {
            if (repository.cancelQueued(id)) {
                audit.record("JOB_CANCEL", job.type() + "#" + id, AuditService.SUCESSO, Map.of("estado", "fila"));
                bus.publish("job." + id, Map.of("type", "done", "jobId", id, "status", JobStatus.CANCELED));
            }
            return repository.findAny(id).orElseThrow();
        }
        if (!job.status().equals(JobStatus.RUNNING)) {
            throw new ApiException(HttpStatus.CONFLICT, "job.nao-esta-ativo", Map.of("status", job.status()));
        }
        Optional<RunningJobs.Running> r = running.get(id);
        if (r.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "job.nao-esta-ativo", Map.of("status", job.status()));
        }
        r.get().token().cancel();
        JsonNode params = json.readTree(job.params());
        for (long machineId : r.get().handler().machinesOf(job, params)) {
            pool.abortAll(machineId);
        }
        audit.record("JOB_CANCEL", job.type() + "#" + id, AuditService.SUCESSO, Map.of("estado", "executando"));
        return job;
    }

    public JobRepository repository() {
        return repository;
    }
}
