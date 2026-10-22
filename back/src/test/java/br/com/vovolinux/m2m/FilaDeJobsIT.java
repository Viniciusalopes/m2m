package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRecovery;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.TestHandlers;
import br.com.vovolinux.m2m.support.TestHandlers.TestJobHandler;
import br.com.vovolinux.m2m.support.Usuarios;
import br.com.vovolinux.m2m.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@Import(TestHandlers.class)
class FilaDeJobsIT {

    @Autowired
    JobService service;
    @Autowired
    JobRepository repository;
    @Autowired
    JobRecovery recovery;
    @Autowired
    TestJobHandler handler;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;

    long owner;
    Usuarios.Criado user;

    @BeforeEach
    void preparar() {
        awaitIdle();
        handler.reset();
        user = usuarios.novo();
        owner = user.id();
    }

    @AfterEach
    void limpar() {
        for (Job j : repository.active()) {
            handler.release(String.valueOf(((tools.jackson.databind.JsonNode) new tools.jackson.databind.json.JsonMapper()
                    .readTree(j.params())).get("name").asText()));
        }
        awaitIdle();
    }

    private void awaitIdle() {
        await().atMost(Duration.ofSeconds(30)).until(() -> repository.active().isEmpty());
    }

    private Job enqueue(String name, String trigger, String mode) {
        return service.enqueue(owner, "TEST", trigger, null, null, Map.of("name", name, "mode", mode));
    }

    private String estado(long id) {
        return repository.findAny(id).orElseThrow().status();
    }

    @Test
    void executaUmJobPorVezNaOrdemDeChegada() {
        Job a = enqueue("a", "SCHEDULED", "wait");
        Job b = enqueue("b", "SCHEDULED", "wait");
        Job c = enqueue("c", "SCHEDULED", "wait");

        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("a"));
        assertThat(estado(a.id())).isEqualTo(JobStatus.RUNNING);
        assertThat(estado(b.id())).isEqualTo(JobStatus.QUEUED);
        assertThat(estado(c.id())).isEqualTo(JobStatus.QUEUED);

        handler.release("a");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("b"));
        handler.release("b");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("c"));
        handler.release("c");
        awaitIdle();

        assertThat(handler.started).containsExactly("a", "b", "c");
        assertThat(handler.maxConcurrent.get()).isEqualTo(1);
        assertThat(estado(a.id())).isEqualTo(JobStatus.COMPLETED);
        assertThat(estado(c.id())).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void agendadoQueChegaDuranteUmJobAguardaNaFila() {
        Job manual = enqueue("manual", "MANUAL", "wait");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("manual"));

        Job agendado = enqueue("agendado", "SCHEDULED", "wait");
        assertThat(estado(agendado.id())).isEqualTo(JobStatus.QUEUED);
        assertThat(handler.started).doesNotContain("agendado");

        handler.release("manual");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("agendado"));
        handler.release("agendado");
        awaitIdle();
        assertThat(estado(manual.id())).isEqualTo(JobStatus.COMPLETED);
        assertThat(estado(agendado.id())).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void pedidoManualComJobAtivoEhRecusadoComOJobEmAndamento() {
        Job primeiro = enqueue("primeiro", "MANUAL", "wait");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("primeiro"));

        assertThatThrownBy(() -> enqueue("segundo", "MANUAL", "wait")).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException a = (ApiException) e;
                    assertThat(a.codigo()).isEqualTo("job.em-andamento");
                    assertThat(a.parametros()).containsEntry("jobId", primeiro.id()).containsEntry("tipo", "TEST");
                });
        // fila na espera também conta como ativa
        enqueue("agendado", "SCHEDULED", "wait");
        handler.release("primeiro");
        assertThatThrownBy(() -> enqueue("terceiro", "MANUAL", "wait")).isInstanceOf(ApiException.class);
        handler.release("agendado");
    }

    @Test
    void falhaEAvisosViramEstadosProprios() {
        Job falha = enqueue("falha", "MANUAL", "fail");
        awaitIdle();
        Job f = repository.findAny(falha.id()).orElseThrow();
        assertThat(f.status()).isEqualTo(JobStatus.FAILED);
        assertThat(f.result()).contains("job.falha-inesperada").contains("falha de teste");

        Job aviso = enqueue("aviso", "MANUAL", "warn");
        awaitIdle();
        assertThat(estado(aviso.id())).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(repository.messages(aviso.id())).extracting(JobRepository.Message::code).containsExactly("teste.aviso");
    }

    @Test
    void emergenciaInterrompeOJobEmExecucaoEmSegundos() throws Exception {
        Job job = enqueue("longo", "MANUAL", "wait");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("longo"));

        long antes = System.nanoTime();
        service.cancel(owner, job.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> estado(job.id()).equals(JobStatus.INTERRUPTED));
        assertThat(Duration.ofNanos(System.nanoTime() - antes)).isLessThan(Duration.ofSeconds(5));
        Job j = repository.findAny(job.id()).orElseThrow();
        assertThat(j.result()).contains("USER");
    }

    @Test
    void emergenciaComFalhaDeEntradaESaidaTambemViraInterrompido() {
        Job job = enqueue("io", "MANUAL", "io");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("io"));
        service.cancel(owner, job.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> estado(job.id()).equals(JobStatus.INTERRUPTED));
    }

    @Test
    void cancelarJobNaFilaNuncaOExecuta() {
        enqueue("ocupa", "MANUAL", "wait");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("ocupa"));
        Job naFila = enqueue("fila", "SCHEDULED", "wait");
        service.cancel(owner, naFila.id());
        assertThat(estado(naFila.id())).isEqualTo(JobStatus.CANCELED);
        handler.release("ocupa");
        awaitIdle();
        assertThat(handler.started).doesNotContain("fila");
    }

    @Test
    void progressoFicaPersistidoEJobAtivoPodeSerReaberto() throws Exception {
        Job job = enqueue("progresso", "MANUAL", "wait");
        await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("progresso"));
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            String p = repository.findAny(job.id()).orElseThrow().progress();
            return p != null && p.contains("arquivo-progresso.txt");
        });
        Job j = repository.findAny(job.id()).orElseThrow();
        assertThat(j.step()).isEqualTo("TESTE");
        String p = j.progress().replace(" ", "");
        assertThat(p).contains("\"bytesDone\":250").contains("\"bytesTotal\":1000").contains("\"percent\":25.0");

        // tela reaberta (outro navegador, mesmo usuário): o job ativo e o progresso vêm da API
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", user.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/jobs/active").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.running.id").value(job.id()))
                .andExpect(jsonPath("$.running.progress.percent").value(25.0))
                .andExpect(jsonPath("$.running.progress.currentPath").value("arquivo-progresso.txt"));
        // outro usuário não enxerga o job
        MockHttpSession outro = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/jobs/active").session(outro)).andExpect(jsonPath("$.running").doesNotExist());
        mvc.perform(get("/api/jobs/" + job.id()).session(outro)).andExpect(status().isNotFound());
    }

    @Test
    void recuperacaoMarcaRunningComoInterrompidoELimpaTemporarios() throws Exception {
        long id = jdbc.sql("""
                insert into job (owner_id, type, status, trigger, params, started_at)
                values (:o, 'TEST', 'RUNNING', 'MANUAL', '{"name":"zumbi"}'::jsonb, now()) returning id""")
                .param("o", owner).query(Long.class).single();
        Path tmp = Path.of("target/test-tmp/job-" + id);
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("parcial.bin"), "lixo");

        recovery.run(null);

        Job j = repository.findAny(id).orElseThrow();
        assertThat(j.status()).isEqualTo(JobStatus.INTERRUPTED);
        assertThat(j.result()).contains("RESTART");
        assertThat(handler.recovered).contains(String.valueOf(id));
        assertThat(Files.exists(tmp)).isFalse();
    }

    @Test
    void jobsNaFilaSaoRepublicadosEExecutadosUmaUnicaVez() {
        // simula a perda da mensagem no broker: job QUEUED sem nenhuma publicação pendente
        long id = jdbc.sql("""
                insert into job (owner_id, type, status, trigger, params)
                values (:o, 'TEST', 'QUEUED', 'SCHEDULED', '{"name":"perdido","mode":"warn"}'::jsonb) returning id""")
                .param("o", owner).query(Long.class).single();

        recovery.run(null);
        recovery.run(null); // reiniciar de novo não pode duplicar

        await().atMost(Duration.ofSeconds(20)).until(() -> estado(id).equals(JobStatus.COMPLETED_WITH_WARNINGS));
        // dá tempo de uma eventual mensagem duplicada ser consumida e ignorada
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(handler.started.stream().filter(n -> n.equals("perdido"))).hasSize(1);
    }

    @Test
    void mensagemDuplicadaDeJobJaExecutadoEhIgnorada() {
        Job job = enqueue("dup", "MANUAL", "warn");
        awaitIdle();
        int antes = handler.started.size();
        // republica à mão uma mensagem do job já concluído
        repository.insertOutbox("JOB", job.id(), "m2m.jobs", "run", "{}");
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(handler.started.size()).isEqualTo(antes);
        assertThat(estado(job.id())).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
    }
}
