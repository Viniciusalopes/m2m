package br.com.vovolinux.m2m.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import br.com.vovolinux.m2m.sync.SyncService.ScheduleRequest;
import br.com.vovolinux.m2m.sync.SyncService.SyncRequest;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sincronização direta: origem LOCAL (sandbox) → destino SFTP em container; nunca máquinas reais (D19). */
@IntegrationTest
@Import(BackupTestSupport.class)
class SincronizacaoIT {

    private static OpenSshServer ssh;

    @Autowired
    BackupTestSupport t;
    @Autowired
    SyncService sync;
    @Autowired
    JobService jobs;
    @Autowired
    JobRepository jobRepo;
    @Autowired
    ProfileService profiles;
    @Autowired
    MachineService machines;
    @Autowired
    ConnectionTester tester;
    @Autowired
    ServerIdentityService identities;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;

    long vps;
    long profile;
    String id;
    Path origem;
    Path destino;

    @BeforeAll
    static void subir() {
        ssh = OpenSshServer.start();
    }

    @AfterAll
    static void parar() {
        ssh.stop();
    }

    @BeforeEach
    void preparar() throws IOException {
        id = UUID.randomUUID().toString().substring(0, 8);
        vps = t.maquinaSftp("Sync-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD, machines,
                tester, identities);
        BackupTestSupport.Sandbox sb = t.novoSandbox();
        origem = sb.origem();
        destino = ssh.homeOnHost().resolve("sync-" + id);
        Files.createDirectories(origem);
        profile = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("Sync " + id, null, t.localMachineId(),
                List.of(new ProfileDtos.Dir(sb.origemPath(),
                        List.of(new ProfileDtos.Mapping(vps, "/config/sync-" + id)))),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
    }

    // ---- utilitários ----

    void escrever(Path raiz, String rel, String conteudo, long mtimeSegundos) throws IOException {
        Path p = raiz.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, conteudo, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(mtimeSegundos)));
    }

    SyncRequest req(boolean conteudo, boolean vazia, boolean destino) {
        return new SyncRequest(vps, conteudo, true, vazia, destino);
    }

    Job manual(SyncRequest r) {
        t.awaitIdle();
        return t.esperar(sync.start(t.adminId(), profile, r).id());
    }

    Job agendada(Map<String, Object> extra) {
        t.awaitIdle();
        Map<String, Object> params = new HashMap<>(extra);
        params.put("profileId", profile);
        params.put("destMachineId", vps);
        Job j = jobs.enqueue(t.adminId(), JobType.SYNC, "SCHEDULED", profile, "Sync " + id, params);
        await().atMost(Duration.ofSeconds(120)).until(() -> JobStatus.isFinal(jobRepo.findAny(j.id()).orElseThrow().status()));
        return jobRepo.findAny(j.id()).orElseThrow();
    }

    JsonNode resultado(Job j) {
        return json.readTree(jobRepo.findAny(j.id()).orElseThrow().result());
    }

    String lerDestino(String rel) throws IOException {
        return Files.readString(destino.resolve(rel));
    }

    /** Cria a base: origem com 3 arquivos, sincronizada manualmente com sucesso. */
    void sincronizarBase() throws IOException {
        escrever(origem, "a.txt", "A", 1_700_000_000L);
        escrever(origem, "sub/b.txt", "B", 1_700_000_000L);
        escrever(origem, "sub/c.txt", "C", 1_700_000_000L);
        Job j = manual(req(false, false, false));
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
    }

    // ---- testes ----

    @Test
    void copiaTudoNaPrimeiraVezEDepoisNadaAAlterar() throws IOException {
        sincronizarBase();
        assertThat(lerDestino("a.txt")).isEqualTo("A");
        assertThat(lerDestino("sub/c.txt")).isEqualTo("C");

        var prev = sync.preview(t.adminId(), profile, req(false, false, false));
        assertThat(prev.alreadySynced()).isTrue();
        assertThat(prev.create() + prev.update() + prev.delete()).isZero();
    }

    @Test
    void soOAlteradoECopiadoEExclusaoEPropagada() throws IOException {
        sincronizarBase();
        escrever(origem, "a.txt", "A2", 1_700_000_100L);
        Files.delete(origem.resolve("sub/c.txt"));
        escrever(origem, "novo.txt", "N", 1_700_000_100L);

        var prev = sync.preview(t.adminId(), profile, req(false, false, false));
        assertThat(prev.create()).isEqualTo(1);
        assertThat(prev.update()).isEqualTo(1);
        assertThat(prev.delete()).isEqualTo(1);

        Job j = manual(req(false, false, false));
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(lerDestino("a.txt")).isEqualTo("A2");
        assertThat(lerDestino("novo.txt")).isEqualTo("N");
        assertThat(lerDestino("sub/b.txt")).isEqualTo("B");
        assertThat(destino.resolve("sub/c.txt")).doesNotExist();
    }

    @Test
    void compararConteudoDetectaMudancaComMesmoTamanhoEData() throws IOException {
        sincronizarBase();
        escrever(origem, "a.txt", "Z", 1_700_000_000L); // mesmo tamanho e mesma data
        assertThat(sync.preview(t.adminId(), profile, req(false, false, false)).alreadySynced()).isTrue();

        var prev = sync.preview(t.adminId(), profile, req(true, false, false));
        assertThat(prev.update()).isEqualTo(1);
        manual(req(true, false, false));
        assertThat(lerDestino("a.txt")).isEqualTo("Z");
    }

    @Test
    void origemAusenteFalhaSemTocarNoDestino() throws IOException {
        sincronizarBase();
        try (var s = Files.walk(origem)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Job j = manual(req(false, true, true));
        assertThat(j.status()).isEqualTo(JobStatus.FAILED);
        assertThat(lerDestino("a.txt")).isEqualTo("A");
    }

    @Test
    void origemVaziaExigeConfirmacaoNaManualEAbortaNaAgendada() throws IOException {
        sincronizarBase();
        try (var s = Files.list(origem)) {
            s.forEach(p -> {
                try (var w = Files.walk(p)) {
                    w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(manual(req(false, false, false)).status()).isEqualTo(JobStatus.FAILED);
        assertThat(lerDestino("a.txt")).isEqualTo("A");

        assertThat(agendada(Map.of()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(lerDestino("a.txt")).isEqualTo("A");

        Job ok = manual(req(false, true, false));
        assertThat(ok.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(destino.resolve("a.txt")).doesNotExist();
    }

    @Test
    void trabalhoNoDestinoAbortaAgendadaEManualConfirma() throws IOException {
        sincronizarBase();
        escrever(destino, "feito-no-destino.txt", "X", 1_700_000_500L);

        var prev = sync.preview(t.adminId(), profile, req(false, false, false));
        assertThat(prev.destinationChanges()).isPositive();

        assertThat(agendada(Map.of()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(destino.resolve("feito-no-destino.txt")).exists();

        assertThat(manual(req(false, false, false)).status()).isEqualTo(JobStatus.FAILED);
        assertThat(destino.resolve("feito-no-destino.txt")).exists();

        Job ok = manual(req(false, false, true));
        assertThat(ok.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(destino.resolve("feito-no-destino.txt")).doesNotExist();
    }

    @Test
    void limiteDeExclusoesPausaAgendadaEConfirmacaoLibera() throws IOException {
        for (int i = 0; i < 10; i++) {
            escrever(origem, "f" + i + ".txt", "v" + i, 1_700_000_000L);
        }
        assertThat(manual(req(false, false, false)).status()).isIn(JobStatus.COMPLETED,
                JobStatus.COMPLETED_WITH_WARNINGS);
        for (int i = 0; i < 6; i++) {
            Files.delete(origem.resolve("f" + i + ".txt"));
        }
        Job pausada = agendada(Map.of("maxDeletionsPercent", 20, "maxDeletionsAbs", 500));
        assertThat(pausada.status()).isEqualTo(JobStatus.AWAITING_CONFIRMATION);
        assertThat(destino.resolve("f0.txt")).exists();

        Job novo = sync.confirmAwaiting(t.adminId(), pausada.id(), null);
        Job fim = t.esperar(novo.id());
        assertThat(fim.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(destino.resolve("f0.txt")).doesNotExist();
        assertThat(destino.resolve("f9.txt")).exists();
    }

    @Test
    void agendamentoExigeManualPreviaERecusaSentidosOpostos() throws IOException {
        var ativar = new ScheduleRequest(vps, "03:00", true, 20, 500);
        assertThatThrownBy(() -> sync.saveSchedule(t.adminId(), profile, null, ativar)).isInstanceOf(ApiException.class)
                .hasMessageContaining("ativar-exige-manual-previa");

        sincronizarBase();
        var view = sync.saveSchedule(t.adminId(), profile, null, ativar);
        assertThat(view.enabled()).isTrue();
        assertThat(view.hasManualSync()).isTrue();

        // perfil inverso: destino (VPS) → origem (LOCAL), mesmos caminhos
        BackupTestSupport.Sandbox sb = t.novoSandbox();
        String sandboxOrigem = "/home/teste/" + origem.getParent().getFileName() + "/origem";
        long inverso = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("Inverso " + id, null, vps,
                List.of(new ProfileDtos.Dir("/config/sync-" + id,
                        List.of(new ProfileDtos.Mapping(t.localMachineId(), sandboxOrigem)))),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        jdbc.sql("""
                insert into sync_run (owner_id, job_id, profile_id, profile_name, dest_machine_id, dest_machine_name, trigger,
                                      status, plan, counts)
                values (:o, null, :p, 'x', :d, 'x', 'MANUAL', 'COMPLETED', '{}'::jsonb, '{}'::jsonb)""")
                .param("o", t.adminId()).param("p", inverso).param("d", t.localMachineId()).update();
        assertThatThrownBy(() -> sync.saveSchedule(t.adminId(), inverso, null,
                new ScheduleRequest(t.localMachineId(), "04:00", true, 20, 500))).isInstanceOf(ApiException.class)
                .hasMessageContaining("sentido-contrario");
    }

    @Test
    void desfazerRestauraSobrescritosEExcluidosERemoveCriados() throws IOException {
        sincronizarBase();
        escrever(origem, "a.txt", "A2", 1_700_000_100L);
        Files.delete(origem.resolve("sub/c.txt"));
        escrever(origem, "novo.txt", "N", 1_700_000_100L);
        Job j = manual(req(false, false, false));
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        long pre = resultado(j).path("preArchiveId").asLong();
        assertThat(pre).isPositive();

        Job u = t.esperar(sync.undo(t.adminId(), pre).id());
        assertThat(u.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(lerDestino("a.txt")).isEqualTo("A");
        assertThat(lerDestino("sub/c.txt")).isEqualTo("C");
        assertThat(destino.resolve("novo.txt")).doesNotExist();
    }

    @Test
    void destinoIgualAOrigemERecusado() {
        assertThatThrownBy(() -> sync.preview(t.adminId(), profile,
                new SyncRequest(t.localMachineId(), false, true, false, false))).isInstanceOf(ApiException.class)
                .hasMessageContaining("destino-igual-origem");
    }

    // ---- arquivo em uso e interrupção ----

    /** Árvore do destino: caminho relativo → "tamanho:hash do conteúdo" (sem temporários do m2m). */
    Map<String, String> retratoDestino() throws Exception {
        Map<String, String> m = new java.util.TreeMap<>();
        if (!Files.exists(destino)) {
            return m;
        }
        try (var st = Files.walk(destino)) {
            for (Path p : (Iterable<Path>) st.skip(1)::iterator) {
                if (Files.isRegularFile(p)) {
                    m.put(destino.relativize(p).toString(), Files.size(p) + ":"
                            + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(p))));
                }
            }
        }
        return m;
    }

    @Test
    void arquivoEmUsoNaOrigemNaoDeixaTemporarioNemCorrompeOsDemais() throws Exception {
        sincronizarBase();
        Path vivo = origem.resolve("vivo.log");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(vivo.toFile(), "rw")) {
            f.setLength(120L * 1024 * 1024);
        }
        escrever(origem, "estavel.txt", "estável", 1_700_000_100L);
        // outro processo continua gravando no arquivo enquanto a sincronização o lê
        var parar = new java.util.concurrent.atomic.AtomicBoolean();
        Thread escritor = Thread.ofPlatform().start(() -> {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(vivo.toFile(), "rw")) {
                while (!parar.get()) {
                    f.seek(f.length() - 1);
                    f.write(1);
                    Files.setLastModifiedTime(vivo, FileTime.fromMillis(System.currentTimeMillis()));
                    Thread.sleep(5);
                }
            } catch (Exception ignorado) {
                // o teste só precisa do arquivo mudando durante a leitura
            }
        });
        Job j;
        try {
            j = manual(req(false, false, false));
        } finally {
            parar.set(true);
            escritor.join();
        }
        // pode terminar com aviso ou ser recusado pela verificação final (a origem seguiu mudando): nunca em silêncio
        assertThat(j.status()).isIn(JobStatus.COMPLETED_WITH_WARNINGS, JobStatus.COMPLETED, JobStatus.FAILED);
        try (var st = Files.walk(destino)) {
            assertThat(st.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".m2m-tmp"));
        }
        assertThat(lerDestino("a.txt")).isEqualTo("A");
        assertThat(lerDestino("estavel.txt")).isEqualTo("estável");
    }

    @Test
    void interrupcaoNoMeioDeixaOPreSyncEODesfazerDevolveODestino() throws Exception {
        sincronizarBase();
        Map<String, String> antes = retratoDestino();
        // mudanças a copiar: um arquivo atualizado, um excluído e um grande novo (dá tempo de interromper)
        escrever(origem, "a.txt", "A-NOVO", 1_700_000_200L);
        Files.delete(origem.resolve("sub/c.txt"));
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(origem.resolve("z-grande.bin").toFile(), "rw")) {
            f.setLength(400L * 1024 * 1024);
        }
        t.awaitIdle();
        Job iniciado = sync.start(t.adminId(), profile, req(false, false, false));
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            var j = jobRepo.findAny(iniciado.id()).orElseThrow();
            return "COPYING".equals(j.step()) || JobStatus.isFinal(j.status());
        });
        jobs.cancel(t.adminId(), iniciado.id());
        Job parado = t.esperar(iniciado.id());
        assertThat(parado.status()).isEqualTo(JobStatus.INTERRUPTED);
        long pre = resultado(parado).path("preArchiveId").asLong();
        assertThat(pre).as(parado.result()).isPositive();
        // nenhum temporário e nenhum arquivo parcial do grande com o nome final
        try (var st = Files.walk(destino)) {
            assertThat(st.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".m2m-tmp"));
        }

        Job u = t.esperar(sync.undo(t.adminId(), pre).id());
        assertThat(u.status()).as(u.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(retratoDestino()).isEqualTo(antes);
    }
}
