package br.com.vovolinux.m2m.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupIT;
import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.RunningJobs;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.restore.RestoreService.RestoreRequest;
import br.com.vovolinux.m2m.restore.RestoreService.SelectiveRequest;
import br.com.vovolinux.m2m.restore.RestoreService.Target;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Restauração em espelho e seletiva: destino LOCAL (sandbox) e destino SFTP em container; nunca máquinas reais
 * (D19).
 */
@IntegrationTest
@Import(BackupTestSupport.class)
class RestauracaoIT {

    private static OpenSshServer ssh;

    @Autowired
    BackupTestSupport t;
    @Autowired
    RestoreService restore;
    @Autowired
    JobService jobs;
    @Autowired
    JobRepository jobRepo;
    @Autowired
    RunningJobs running;
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
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    long vps;
    String id;

    @BeforeAll
    static void subir() {
        ssh = OpenSshServer.start();
    }

    @AfterAll
    static void parar() {
        ssh.stop();
    }

    @BeforeEach
    void preparar() {
        id = UUID.randomUUID().toString().substring(0, 8);
        vps = t.maquinaSftp("Destino-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD,
                machines, tester, identities);
    }

    // ---- utilitários ----

    /** Retrato de uma árvore: caminho relativo → "tipo:modo:conteúdo/alvo" (sem datas de diretório). */
    static Map<String, String> retrato(Path raiz) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (!Files.exists(raiz, LinkOption.NOFOLLOW_LINKS)) {
            return m;
        }
        try (Stream<Path> s = Files.walk(raiz)) {
            for (Path p : (Iterable<Path>) s.skip(1)::iterator) {
                String rel = raiz.relativize(p).toString();
                if (Files.isSymbolicLink(p)) {
                    m.put(rel, "L::" + Files.readSymbolicLink(p));
                } else if (Files.isDirectory(p)) {
                    m.put(rel, "D:" + Integer.toOctalString(modo(p)));
                } else if (Files.isRegularFile(p)) {
                    m.put(rel, "F:" + Integer.toOctalString(modo(p)) + ":" + sha(p));
                }
            }
        }
        return m;
    }

    static String sha(Path p) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static int modo(Path p) throws IOException {
        int mode = 0;
        for (var perm : Files.getPosixFilePermissions(p)) {
            mode |= switch (perm) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return mode;
    }

    private static void escrever(Path p, String conteudo, long epoch) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, conteudo);
        Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(epoch)));
    }

    /** Perfil com origem LOCAL, mapeamento para o servidor SFTP, e um backup concluído. */
    private record Cenario(BackupTestSupport.Sandbox sb, long perfil, BackupArchive backup) {
    }

    private Cenario cenarioComBackup(String mapeamentoVps) throws Exception {
        var sb = t.novoSandbox();
        BackupIT.montarOrigem(sb.origem());
        Files.writeString(sb.origem().resolve("original.txt"), "base");
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("rest-" + id, null,
                t.localMachineId(),
                List.of(new ProfileDtos.Dir(sb.origemPath(), mapeamentoVps == null ? List.of()
                        : List.of(new ProfileDtos.Mapping(vps, mapeamentoVps)))),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        Job job = t.executar(perfil, null);
        assertThat(job.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        return new Cenario(sb, perfil, t.arquivos(perfil).get(0));
    }

    private Job restaurar(BackupArchive b, long machineId, String path) {
        t.awaitIdle();
        Job j = restore.start(t.adminId(), b.id(), new RestoreRequest(machineId, List.of(new Target(1, path)), true),
                false);
        return t.esperar(j.id());
    }

    // ---- espelho ----

    @Test
    void espelhoNoServidorSftpSobrescreveCriaEExcluiPreservandoOForaDoEscopo() throws Exception {
        Cenario c = cenarioComBackup("/config/espelho-" + id);
        Path destino = ssh.homeOnHost().resolve("espelho-" + id);
        Path vizinho = ssh.homeOnHost().resolve("vizinho-" + id);
        // destino com versões antigas/mais novas, extras (inclusive ocultos e temporários) e um vizinho fora do escopo
        escrever(destino.resolve("build.sh"), "versão MAIS NOVA no destino", 2_000_000_000L);
        escrever(destino.resolve(".env"), "SEGREDO=velho", 1_000_000_000L);
        escrever(destino.resolve(".cache/x.tmp"), "extra", 1_700_000_000L);
        escrever(destino.resolve("extra-dir/a.txt"), "extra", 1_700_000_000L);
        escrever(vizinho.resolve("intacto.txt"), "não mexer", 1_700_000_000L);
        Map<String, String> vizinhoAntes = retrato(vizinho);
        Map<String, String> destinoAntes = retrato(destino);

        Job job = restaurar(c.backup(), vps, "/config/espelho-" + id);

        assertThat(job.status()).as(job.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        // o destino ficou idêntico à origem (conteúdo, permissões, links, ocultos, vazios), sem os extras
        Map<String, String> origem = retrato(c.sb().origem());
        Map<String, String> depois = retrato(destino);
        assertThat(depois).isEqualTo(origem);
        assertThat(depois).containsKey(".env").doesNotContainKey(".cache/x.tmp").doesNotContainKey("extra-dir");
        assertThat(Files.getLastModifiedTime(destino.resolve("build.sh")).toInstant())
                .isEqualTo(Instant.parse("2021-03-04T05:06:07Z"));
        // fora do escopo: intacto
        assertThat(retrato(vizinho)).isEqualTo(vizinhoAntes);
        // nenhum temporário ficou
        try (Stream<Path> s = Files.walk(destino)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".m2m-tmp"));
        }

        // backup de segurança: gerado e validado antes, com o conteúdo antigo do destino
        BackupArchive pre = t.arquivos(c.perfil()).stream().filter(a -> a.type().equals("PRE_RESTORE")).findFirst()
                .orElseThrow();
        assertThat(pre.fileName()).matches("rest-\\Q" + id + "\\E_pre-restore_Destino-\\Q" + id + "\\E_\\d{8}-\\d{6}\\.tar\\.zst");
        Path extraido = c.sb().armazenamento().resolveSibling("pre-extraido");
        BackupIT.extrairComTar(c.sb().armazenamento().resolve(pre.fileName()), extraido);
        assertThat(retrato(extraido.resolve("dir-1"))).isEqualTo(destinoAntes);

        // histórico da restauração
        JsonNode run = json.readTree(json.writeValueAsString(jdbc.sql("select status, pre_restore_archive_id from restore_run where job_id = :j")
                .param("j", job.id()).query((rs, i) -> Map.of("status", rs.getString(1), "pre", rs.getLong(2))).single()));
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("pre").asLong()).isEqualTo(pre.id());

        // desfazer: devolve o destino ao estado anterior à restauração
        t.awaitIdle();
        Job undo = t.esperar(restore.undo(t.adminId(), pre.id()).id());
        assertThat(undo.status()).as(undo.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(retrato(destino)).isEqualTo(destinoAntes);
        assertThat(retrato(vizinho)).isEqualTo(vizinhoAntes);
    }

    @Test
    void restauracaoNoDestinoLocalEmCaminhoQueAindaNaoExiste() throws Exception {
        Cenario c = cenarioComBackup(null);
        Path novo = c.sb().armazenamento().resolveSibling("fundo/a/b/restaurado");
        String path = "/home/teste/" + c.sb().origem().getParent().getFileName() + "/fundo/a/b/restaurado";
        Job job = restaurar(c.backup(), t.localMachineId(), path);
        assertThat(job.status()).as(job.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(retrato(novo)).isEqualTo(retrato(c.sb().origem()));
        // o backup de segurança de um destino inexistente só guarda o que precisa para desfazer
        BackupArchive pre = t.arquivos(c.perfil()).stream().filter(a -> a.type().equals("PRE_RESTORE")).findFirst()
                .orElseThrow();
        assertThat(pre.fileCount()).isZero();
        // desfazer remove o que a restauração criou
        t.awaitIdle();
        t.esperar(restore.undo(t.adminId(), pre.id()).id());
        assertThat(Files.exists(novo)).isFalse();
    }

    @Test
    void caminhosPadraoVemDoManifestoMesmoSeOPerfilMudarDepois() throws Exception {
        Cenario c = cenarioComBackup("/config/mapeado-" + id);
        // o mapeamento do perfil muda depois do backup
        profiles.update(t.adminId(), c.perfil(), new ProfileDtos.ProfileRequest("rest-" + id, null, t.localMachineId(),
                List.of(new ProfileDtos.Dir(c.sb().origemPath(),
                        List.of(new ProfileDtos.Mapping(vps, "/config/mudou-" + id)))),
                new ProfileDtos.Storage(t.localMachineId(), c.sb().armazenamentoPath()), 0.5, false, null));
        var padrao = restore.defaults(t.adminId(), c.backup().id(), vps);
        assertThat(padrao).hasSize(1);
        assertThat(padrao.get(0).targetPath()).isEqualTo("/config/mapeado-" + id);
        // destino = a própria origem: o caminho original
        assertThat(restore.defaults(t.adminId(), c.backup().id(), t.localMachineId()).get(0).targetPath())
                .isEqualTo(c.sb().origemPath());
    }

    @Test
    void destinoSemMapeamentoExigeOCaminho() throws Exception {
        Cenario c = cenarioComBackup(null);
        assertThat(restore.defaults(t.adminId(), c.backup().id(), vps).get(0).targetPath()).isNull();
        assertThatThrownBy(() -> restore.start(t.adminId(), c.backup().id(),
                new RestoreRequest(vps, List.of(), true), false)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo()).isEqualTo("restauracao.destino-sem-caminho");
    }

    @Test
    void semConfirmacaoNadaAcontece() throws Exception {
        Cenario c = cenarioComBackup(null);
        assertThatThrownBy(() -> restore.start(t.adminId(), c.backup().id(),
                new RestoreRequest(vps, List.of(new Target(1, "/config/x-" + id)), false), false))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).codigo())
                .isEqualTo("restauracao.confirmacao-obrigatoria");
        assertThat(Files.exists(ssh.homeOnHost().resolve("x-" + id))).isFalse();
    }

    @Test
    void previaContaCriarSobrescreverEExcluir() throws Exception {
        Cenario c = cenarioComBackup("/config/previa-" + id);
        Path destino = ssh.homeOnHost().resolve("previa-" + id);
        escrever(destino.resolve("original.txt"), "base", 1_700_000_000L); // existe nos dois
        escrever(destino.resolve("so-no-destino.txt"), "x", 1_700_000_000L);
        var p = restore.preview(t.adminId(), c.backup().id(),
                new RestoreRequest(vps, List.of(new Target(1, "/config/previa-" + id)), true));
        assertThat(p.directories()).hasSize(1);
        var d = p.directories().get(0);
        assertThat(d.targetExists()).isTrue();
        assertThat(d.overwrite()).isEqualTo(1); // original.txt
        assertThat(d.delete()).isEqualTo(1); // so-no-destino.txt
        assertThat(d.create()).isGreaterThan(10); // todo o resto
        assertThat(p.warnings()).isEmpty(); // SFTP: fidelidade total
        // a prévia não altera nada
        assertThat(Files.exists(destino.resolve("so-no-destino.txt"))).isTrue();
        assertThat(Files.exists(destino.resolve("build.sh"))).isFalse();
    }

    @Test
    void backupCorrompidoCancelaARestauracaoSemAlterarODestino() throws Exception {
        Cenario c = cenarioComBackup("/config/corrompido-" + id);
        Path destino = ssh.homeOnHost().resolve("corrompido-" + id);
        escrever(destino.resolve("arquivo-do-usuario.txt"), "meu trabalho", 1_700_000_000L);
        Map<String, String> antes = retrato(destino);
        // corrompe um byte no meio do arquivo de backup
        Path arq = c.sb().armazenamento().resolve(c.backup().fileName());
        byte[] bytes = Files.readAllBytes(arq);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(arq, bytes);

        Job job = restaurar(c.backup(), vps, "/config/corrompido-" + id);

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.result()).contains("restauracao.backup-corrompido");
        assertThat(retrato(destino)).isEqualTo(antes);
        assertThat(t.arquivos(c.perfil()).get(0).status()).isEqualTo("CORRUPTED");
        // um backup corrompido não é oferecido nem aceito para restaurar
        assertThatThrownBy(() -> restore.defaults(t.adminId(), c.backup().id(), vps)).isInstanceOf(ApiException.class);
    }

    @Test
    void falhaNoBackupDeSegurancaCancelaARestauracaoSemAlterarODestino() throws Exception {
        Cenario c = cenarioComBackup("/config/semseguranca-" + id);
        Path destino = ssh.homeOnHost().resolve("semseguranca-" + id);
        escrever(destino.resolve("meu.txt"), "meu trabalho", 1_700_000_000L);
        Map<String, String> antes = retrato(destino);
        // o armazenamento deixa de ser gravável: o backup de segurança falha
        Files.setPosixFilePermissions(c.sb().armazenamento(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Job job = restaurar(c.backup(), vps, "/config/semseguranca-" + id);
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
            assertThat(job.result()).contains("restauracao.backup-de-seguranca-falhou");
        } finally {
            Files.setPosixFilePermissions(c.sb().armazenamento(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(retrato(destino)).isEqualTo(antes);
    }

    @Test
    void emergenciaNaCopiaDeixaDestinoParcialEODesfazerRecuperaOEstadoAnterior() throws Exception {
        var sb = t.novoSandbox();
        // origem grande o bastante para dar tempo de interromper: muitos arquivos de 2 MiB incompressíveis
        Random rnd = new Random(3);
        byte[] bloco = new byte[2 << 20];
        for (int i = 0; i < 40; i++) {
            rnd.nextBytes(bloco);
            try (OutputStream out = Files.newOutputStream(sb.origem().resolve("f" + i + ".bin"))) {
                out.write(bloco);
            }
        }
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("canc-" + id, null,
                t.localMachineId(), List.of(new ProfileDtos.Dir(sb.origemPath(), List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        assertThat(t.executar(perfil, null).status()).isEqualTo(JobStatus.COMPLETED);
        BackupArchive backup = t.arquivos(perfil).get(0);

        Path destino = ssh.homeOnHost().resolve("parcial-" + id);
        escrever(destino.resolve("antes.txt"), "estado anterior", 1_700_000_000L);
        escrever(destino.resolve("sub/outro.txt"), "outro", 1_700_000_000L);
        Map<String, String> antes = retrato(destino);

        t.awaitIdle();
        Job job = restore.start(t.adminId(), backup.id(),
                new RestoreRequest(vps, List.of(new Target(1, "/config/parcial-" + id)), true), false);
        await().atMost(Duration.ofSeconds(120)).until(() -> running.get(job.id())
                .map(r -> "COPYING".equals(r.progress().step())
                        && ((Number) r.progress().snapshot().get("bytesDone")).longValue() > 4_000_000L)
                .orElse(false));
        jobs.cancel(t.adminId(), job.id());
        Job fim = t.esperar(job.id());

        assertThat(fim.status()).isEqualTo(JobStatus.INTERRUPTED);
        assertThat(fim.result().replace(" ", "")).contains("\"partial\":true").contains("preArchiveId");
        long preId = json.readTree(fim.result()).get("preArchiveId").asLong();
        assertThat(retrato(destino)).isNotEqualTo(antes); // destino em estado parcial
        try (Stream<Path> s = Files.walk(destino)) {
            // a emergência não deixa arquivo incompleto com o nome final: só temporários
            assertThat(s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".bin")).toList()).allMatch(n -> {
                        try {
                            return Files.size(destino.resolve(n)) == (2 << 20);
                        } catch (IOException e) {
                            return false;
                        }
                    });
        }

        t.awaitIdle();
        Job undo = t.esperar(restore.undo(t.adminId(), preId).id());
        assertThat(undo.status()).as(undo.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(retrato(destino)).isEqualTo(antes);
    }

    // ---- seletiva ----

    @Test
    void seletivaExtraiSoOEscolhidoPreservandoAEstruturaESemExcluirNada() throws Exception {
        var sb = t.novoSandbox();
        escrever(sb.origem().resolve("app/src/Main.java"), "class Main {}", 1_700_000_000L);
        escrever(sb.origem().resolve("app/src/Util.java"), "class Util {}", 1_700_000_000L);
        escrever(sb.origem().resolve("docs/leia.md"), "# doc", 1_700_000_000L);
        long perfil = t.criarPerfil(sb, "sel-" + id);
        t.executar(perfil, null);
        BackupArchive b = t.arquivos(perfil).get(0);

        Path alvo = ssh.homeOnHost().resolve("recuperados-" + id);
        escrever(alvo.resolve("ja-existia.txt"), "não pode ser apagado", 1_700_000_000L);
        t.awaitIdle();
        Job job = restore.startSelective(t.adminId(), b.id(), new SelectiveRequest(vps, "/config/recuperados-" + id,
                List.of("dir-1/app/src/Main.java"), "SKIP", Map.of()));
        Job fim = t.esperar(job.id());

        assertThat(fim.status()).as(fim.result()).isEqualTo(JobStatus.COMPLETED);
        assertThat(alvo.resolve("app/src/Main.java")).hasContent("class Main {}");
        assertThat(alvo.resolve("app/src/Util.java")).doesNotExist();
        assertThat(alvo.resolve("docs")).doesNotExist();
        assertThat(alvo.resolve("ja-existia.txt")).hasContent("não pode ser apagado"); // nada excluído
    }

    @Test
    void seletivaDeDiretorioETodosOsConflitosComAplicarATodos() throws Exception {
        var sb = t.novoSandbox();
        escrever(sb.origem().resolve("docs/a.txt"), "novo A", 1_700_000_100L);
        escrever(sb.origem().resolve("docs/b.txt"), "novo B", 1_700_000_100L);
        escrever(sb.origem().resolve("docs/c.txt"), "novo C", 1_700_000_100L);
        long perfil = t.criarPerfil(sb, "sel2-" + id);
        t.executar(perfil, null);
        BackupArchive b = t.arquivos(perfil).get(0);

        Path alvo = ssh.homeOnHost().resolve("conflitos-" + id);
        escrever(alvo.resolve("docs/a.txt"), "velho A", 1_700_000_000L);
        escrever(alvo.resolve("docs/b.txt"), "velho B", 1_700_000_000L);
        SelectiveRequest req = new SelectiveRequest(vps, "/config/conflitos-" + id, List.of("dir-1/docs"), "SKIP",
                Map.of());
        var conflitos = restore.plan(t.adminId(), b.id(), req);
        assertThat(conflitos).extracting(RestoreService.Conflict::entry)
                .containsExactlyInAnyOrder("dir-1/docs/a.txt", "dir-1/docs/b.txt");

        // a: sobrescrever; b: renomear; c (sem conflito) é criado
        t.awaitIdle();
        Job job = restore.startSelective(t.adminId(), b.id(), new SelectiveRequest(vps, "/config/conflitos-" + id,
                List.of("dir-1/docs"), "SKIP", Map.of("dir-1/docs/a.txt", "OVERWRITE", "dir-1/docs/b.txt", "RENAME")));
        Job fim = t.esperar(job.id());
        assertThat(fim.status()).as(fim.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(alvo.resolve("docs/a.txt")).hasContent("novo A");
        assertThat(alvo.resolve("docs/b.txt")).hasContent("velho B"); // preservado
        assertThat(alvo.resolve("docs/b (1).txt")).hasContent("novo B");
        assertThat(alvo.resolve("docs/c.txt")).hasContent("novo C");

        // aplicar a todos: política "sobrescrever se mais novo" pula o que já está atualizado
        t.awaitIdle();
        Job de = t.esperar(restore.startSelective(t.adminId(), b.id(), new SelectiveRequest(vps,
                "/config/conflitos-" + id, List.of("dir-1/docs"), "OVERWRITE_NEWER", Map.of())).id());
        assertThat(de.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(alvo.resolve("docs/a.txt")).hasContent("novo A");
    }

    @Test
    void fluxoPelaApiHttpComPreviaConfirmacaoEHistorico() throws Exception {
        Cenario c = cenarioComBackup("/config/http-" + id);
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/backups/" + c.backup().id() + "/restore/defaults").param("machineId", String.valueOf(vps))
                .session(s)).andExpect(status().isOk()).andExpect(jsonPath("$[0].targetPath").value("/config/http-" + id));
        String corpo = "{\"machineId\":" + vps + ",\"targets\":[{\"dir\":1,\"path\":\"/config/http-" + id + "\"}],\"confirmed\":%s}";
        mvc.perform(post("/api/backups/" + c.backup().id() + "/restore/preview").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(corpo.formatted("false")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.create").isNumber());
        mvc.perform(post("/api/backups/" + c.backup().id() + "/restore").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(corpo.formatted("false")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("restauracao.confirmacao-obrigatoria"));
        t.awaitIdle();
        String resp = mvc.perform(post("/api/backups/" + c.backup().id() + "/restore").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(corpo.formatted("true")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        t.esperar(json.readTree(resp).get("id").asLong());
        mvc.perform(get("/api/restores").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED")).andExpect(jsonPath("$[0].selective").value(false));
        assertThat(jobRepo.findAny(json.readTree(resp).get("id").asLong()).orElseThrow().status()).isNotNull();
    }
}
