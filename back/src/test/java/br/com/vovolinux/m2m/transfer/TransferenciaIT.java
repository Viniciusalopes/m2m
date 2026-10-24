package br.com.vovolinux.m2m.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import br.com.vovolinux.m2m.transfer.TransferFilter.Condition;
import br.com.vovolinux.m2m.transfer.TransferFilter.Definition;
import br.com.vovolinux.m2m.transfer.TransferService.PlanRequest;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transferência manual: Local (sandbox) ↔ SFTP em container ↔ SFTP em container; nunca máquinas reais (D19). Os dois
 * "servidores" SFTP são o mesmo container cadastrado duas vezes: o caminho remoto↔remoto passa pelo host do mesmo jeito.
 */
@IntegrationTest
@Import(BackupTestSupport.class)
class TransferenciaIT {

    private static OpenSshServer ssh;

    @Autowired
    BackupTestSupport t;
    @Autowired
    TransferService service;
    @Autowired
    TransferRepository repo;
    @Autowired
    TransferRecovery recovery;
    @Autowired
    RunningTransfers running;
    @Autowired
    MachineService machines;
    @Autowired
    ConnectionTester tester;
    @Autowired
    ServerIdentityService identities;
    @Autowired
    ProfileService profiles;
    @Autowired
    JobRepository jobRepo;
    @Autowired
    JsonMapper json;

    long a;
    long b;
    long local;
    String id;
    Path origem;
    String origemPath;
    Path homeA;

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
        a = t.maquinaSftp("TA-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD, machines,
                tester, identities);
        b = t.maquinaSftp("TB-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD, machines,
                tester, identities);
        local = t.localMachineId();
        var sb = t.novoSandbox();
        origem = sb.origem();
        origemPath = sb.origemPath();
        homeA = ssh.homeOnHost();
    }

    // ---- utilitários ----

    String remote(String rel) {
        return "/config/" + id + "/" + rel;
    }

    Path remoteHost(String rel) {
        return homeA.resolve(id).resolve(rel);
    }

    void write(Path base, String rel, String content, long mtimeSec) throws IOException {
        Path p = base.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeSec * 1000));
    }

    PlanRequest req(long src, List<String> paths, long dst, String dstDir) {
        return new PlanRequest(src, paths, dst, dstDir, false, null, null, null);
    }

    PlanRequest withDefault(PlanRequest r, String action) {
        return new PlanRequest(r.srcMachineId(), r.srcPaths(), r.dstMachineId(), r.dstDir(), r.applyFilters(),
                r.filter(), action, r.decisions());
    }

    List<TransferItem> aguardar(String batchId) {
        UUID batch = UUID.fromString(batchId);
        await().atMost(Duration.ofSeconds(180)).until(() -> repo.pendingInBatch(batch) == 0);
        return repo.list(t.adminId(), null, batch, 1000, 0);
    }

    TransferItem item(List<TransferItem> items, String dstSuffix) {
        return items.stream().filter(i -> i.dstPath().endsWith(dstSuffix)).findFirst().orElseThrow();
    }

    String enviar(PlanRequest r) {
        return service.start(t.adminId(), r).batchId();
    }

    // ---- testes ----

    @Test
    void enviaPastaDoLocalParaOSftpComEstruturaOcultosVaziosLinksEPermissoes() throws IOException {
        write(origem, "projeto/a.txt", "A", 1_700_000_000L);
        write(origem, "projeto/.oculto", "H", 1_700_000_000L);
        write(origem, "projeto/sub/b.txt", "B", 1_700_000_100L);
        Files.createDirectories(origem.resolve("projeto/vazio"));
        Files.createSymbolicLink(origem.resolve("projeto/link"), Path.of("a.txt"));
        Files.setPosixFilePermissions(origem.resolve("projeto/a.txt"), PosixFilePermissions.fromString("rw-r-----"));
        boolean fifo = false;
        try {
            fifo = new ProcessBuilder("mkfifo", origem.resolve("projeto/pipe").toString()).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            // sem mkfifo no host: o caso do arquivo especial não é verificado
        }

        var plan = service.plan(t.adminId(), req(local, List.of(origemPath + "/projeto"), a, "/config/" + id));
        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.files()).isEqualTo(3);

        List<TransferItem> items = aguardar(enviar(req(local, List.of(origemPath + "/projeto"), a, "/config/" + id)));
        assertThat(items).allMatch(i -> i.status().equals("COMPLETED") || i.status().equals("SKIPPED"));
        assertThat(Files.readString(remoteHost("projeto/a.txt"))).isEqualTo("A");
        assertThat(Files.readString(remoteHost("projeto/.oculto"))).isEqualTo("H");
        assertThat(Files.readString(remoteHost("projeto/sub/b.txt"))).isEqualTo("B");
        assertThat(Files.isDirectory(remoteHost("projeto/vazio"))).isTrue();
        assertThat(Files.readSymbolicLink(remoteHost("projeto/link")).toString()).isEqualTo("a.txt");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(remoteHost("projeto/a.txt"))))
                .isEqualTo("rw-r-----");
        assertThat(Files.getLastModifiedTime(remoteHost("projeto/sub/b.txt")).toMillis() / 1000)
                .isBetween(1_700_000_098L, 1_700_000_102L);
        if (fifo) {
            assertThat(items).anyMatch(i -> i.status().equals("SKIPPED") && "item-especial".equals(i.skipReason()));
            assertThat(Files.exists(remoteHost("projeto/pipe"))).isFalse();
        }
        assertThat(Files.exists(remoteHost("projeto/.a.txt.m2m-tmp"))).isFalse();
    }

    @Test
    void baixaDoSftpParaOLocalERemotoParaRemoto() throws IOException {
        write(homeA.resolve(id), "dados/x.bin", "XXXX", 1_700_000_000L);
        write(homeA.resolve(id), "dados/y/z.txt", "ZZ", 1_700_000_000L);

        // SFTP → Local
        Path destinoLocal = origem.resolveSibling("baixados");
        Files.createDirectories(destinoLocal);
        String destLocalPath = origemPath.replace("/origem", "/baixados");
        aguardar(enviar(req(a, List.of(remote("dados")), local, destLocalPath)));
        assertThat(Files.readString(destinoLocal.resolve("dados/x.bin"))).isEqualTo("XXXX");
        assertThat(Files.readString(destinoLocal.resolve("dados/y/z.txt"))).isEqualTo("ZZ");

        // remoto → remoto (A → B, em streaming pelo host)
        aguardar(enviar(req(a, List.of(remote("dados/x.bin")), b, "/config/" + id + "/copia")));
        assertThat(Files.readString(remoteHost("copia/x.bin"))).isEqualTo("XXXX");
    }

    @Test
    void sieteAcoesDeConflito() throws IOException {
        // destino já tem: mesmo conteúdo antigo (mais velho), tamanho igual mais novo, tamanho diferente
        write(origem, "c/mais-novo.txt", "NOVO", 1_700_000_500L);
        write(origem, "c/mesmo-tamanho.txt", "1234", 1_700_000_500L);
        write(origem, "c/tamanho-diferente.txt", "123456", 1_700_000_100L);
        write(homeA.resolve(id), "c/mais-novo.txt", "VELH", 1_700_000_100L);
        write(homeA.resolve(id), "c/mesmo-tamanho.txt", "abcd", 1_700_000_900L);
        write(homeA.resolve(id), "c/tamanho-diferente.txt", "xx", 1_700_000_900L);
        PlanRequest base = req(local, List.of(origemPath + "/c"), a, "/config/" + id);

        var plan = service.plan(t.adminId(), base);
        assertThat(plan.conflicts()).hasSize(3);
        assertThatThrownBy(() -> service.start(t.adminId(), base)).isInstanceOf(ApiException.class)
                .hasMessageContaining("conflitos-sem-decisao");

        // pular, aplicando a todos
        List<TransferItem> skip = aguardar(enviar(withDefault(base, "SKIP")));
        assertThat(skip).filteredOn(i -> i.kind().equals("FILE")).allMatch(i -> i.status().equals("SKIPPED"));
        assertThat(Files.readString(remoteHost("c/mais-novo.txt"))).isEqualTo("VELH");

        // sobrescrever se mais novo: só o arquivo cuja origem é mais nova
        aguardar(enviar(withDefault(base, "OVERWRITE_NEWER")));
        assertThat(Files.readString(remoteHost("c/mais-novo.txt"))).isEqualTo("NOVO");
        assertThat(Files.readString(remoteHost("c/mesmo-tamanho.txt"))).isEqualTo("abcd");
        assertThat(Files.readString(remoteHost("c/tamanho-diferente.txt"))).isEqualTo("xx");

        // sobrescrever se o tamanho for diferente: o de mesmo tamanho é pulado
        aguardar(enviar(withDefault(base, "OVERWRITE_SIZE")));
        assertThat(Files.readString(remoteHost("c/mesmo-tamanho.txt"))).isEqualTo("abcd");
        assertThat(Files.readString(remoteHost("c/tamanho-diferente.txt"))).isEqualTo("123456");

        // tamanho diferente OU mais novo: o de mesmo tamanho e origem mais velha continua
        write(homeA.resolve(id), "c/tamanho-diferente.txt", "yy", 1_700_000_900L);
        aguardar(enviar(withDefault(base, "OVERWRITE_SIZE_OR_NEWER")));
        assertThat(Files.readString(remoteHost("c/tamanho-diferente.txt"))).isEqualTo("123456");
        assertThat(Files.readString(remoteHost("c/mesmo-tamanho.txt"))).isEqualTo("abcd");

        // renomear: cria "nome (1).ext" sem tocar no existente
        aguardar(enviar(withDefault(base, "RENAME")));
        assertThat(Files.readString(remoteHost("c/mais-novo (1).txt"))).isEqualTo("NOVO");
        assertThat(Files.readString(remoteHost("c/mais-novo.txt"))).isEqualTo("NOVO");

        // sobrescrever sempre, e decisão individual por arquivo
        write(homeA.resolve(id), "c/mesmo-tamanho.txt", "zzzz", 1_700_000_900L);
        Map<String, String> decisions = new HashMap<>();
        decisions.put(remote("c/mesmo-tamanho.txt"), "SKIP");
        decisions.put(remote("c/mais-novo.txt"), "OVERWRITE");
        decisions.put(remote("c/tamanho-diferente.txt"), "OVERWRITE");
        aguardar(enviar(new PlanRequest(local, base.srcPaths(), a, base.dstDir(), false, null, null, decisions)));
        assertThat(Files.readString(remoteHost("c/mesmo-tamanho.txt"))).isEqualTo("zzzz");
    }

    @Test
    void conflitoRetomarContinuaOArquivoFinalMenor() throws IOException {
        byte[] full = new byte[2_000_000];
        new Random(7).nextBytes(full);
        Files.write(origem.resolve("grande.bin"), full);
        Path parcial = homeA.resolve(id).resolve("grande.bin");
        Files.createDirectories(parcial.getParent());
        Files.write(parcial, java.util.Arrays.copyOf(full, 800_000));

        aguardar(enviar(withDefault(req(local, List.of(origemPath + "/grande.bin"), a, "/config/" + id), "RESUME")));
        assertThat(Files.readAllBytes(parcial)).isEqualTo(full);
    }

    @Test
    void retomadaAposQuedaContinuaDoPontoEOrigemAlteradaRecomeca() throws IOException {
        byte[] full = new byte[3_000_000];
        new Random(11).nextBytes(full);
        Path src = origem.resolve("big.bin");
        Files.write(src, full);
        long mtime = Files.getLastModifiedTime(src).toMillis();

        long itemId = itemInterrompido("big.bin", full.length, mtime, 1_000_000);
        Files.createDirectories(remoteHost("."));
        Files.write(remoteHost(".big.bin.m2m-tmp"), java.util.Arrays.copyOf(full, 1_000_000));
        service.resume(t.adminId(), itemId);
        TransferItem ok = esperarItem(itemId);
        assertThat(ok.status()).isEqualTo("COMPLETED");
        assertThat(ok.note()).isEqualTo("retomado");
        assertThat(Files.readAllBytes(remoteHost("big.bin"))).isEqualTo(full);
        assertThat(Files.exists(remoteHost(".big.bin.m2m-tmp"))).isFalse();

        // origem alterada depois da queda: não retoma, recomeça do zero e informa o motivo
        Files.delete(remoteHost("big.bin"));
        long outro = itemInterrompido("big.bin", full.length, mtime, 1_000_000);
        Files.write(remoteHost(".big.bin.m2m-tmp"), java.util.Arrays.copyOf(full, 1_000_000));
        byte[] novo = new byte[3_000_000];
        new Random(12).nextBytes(novo);
        Files.write(src, novo);
        service.resume(t.adminId(), outro);
        TransferItem refeito = esperarItem(outro);
        assertThat(refeito.status()).isEqualTo("COMPLETED");
        assertThat(refeito.note()).isEqualTo("retomada-recusada-origem-alterada");
        assertThat(Files.readAllBytes(remoteHost("big.bin"))).isEqualTo(novo);
    }

    /** Cria um item como se tivesse caído no meio: FAILED, com tamanho e data da origem e o deslocamento gravado. */
    long itemInterrompido(String nome, long size, long mtime, long offset) {
        UUID batch = UUID.randomUUID();
        long itemId = repo.insert(new TransferRepository.NewItem(t.adminId(), batch, "COPY", "FILE", "QUEUED", local,
                "HP", origemPath + "/" + nome, a, "TA", remote(nome), size, mtime, 0644, null, false, "OVERWRITE", null));
        repo.claim(itemId);
        repo.markStarted(itemId, size, mtime, "/config/" + id + "/." + nome + ".m2m-tmp", offset, null);
        repo.finish(itemId, "FAILED", offset, offset, null, "conexao.falha", null, null);
        return itemId;
    }

    TransferItem esperarItem(long itemId) {
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            String s = repo.findAny(itemId).orElseThrow().status();
            return !s.equals("QUEUED") && !s.equals("TRANSFERRING");
        });
        return repo.findAny(itemId).orElseThrow();
    }

    @Test
    void cancelarNoMeioNaoCorrompeOAnteriorEPermiteRetomar() throws IOException {
        Path big = origem.resolve("enorme.bin");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(500L * 1024 * 1024);
        }
        write(homeA.resolve(id), "enorme.bin", "VERSAO-ANTERIOR", 1_700_000_000L);
        String batch = enviar(withDefault(req(local, List.of(origemPath + "/enorme.bin"), a, "/config/" + id),
                "OVERWRITE"));
        UUID batchId = UUID.fromString(batch);
        TransferItem it = repo.list(t.adminId(), null, batchId, 10, 0).get(0);
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            RunningTransfers.Handle h = running.get(it.id());
            return h != null && h.bytes > 8L * 1024 * 1024;
        });
        service.cancel(t.adminId(), it.id());
        TransferItem parado = esperarItem(it.id());
        assertThat(parado.status()).isEqualTo("CANCELED");
        assertThat(Files.readString(remoteHost("enorme.bin"))).isEqualTo("VERSAO-ANTERIOR");
        assertThat(Files.exists(remoteHost(".enorme.bin.m2m-tmp"))).isTrue();

        service.resume(t.adminId(), it.id());
        TransferItem fim = esperarItem(it.id());
        assertThat(fim.status()).isEqualTo("COMPLETED");
        assertThat(Files.size(remoteHost("enorme.bin"))).isEqualTo(500L * 1024 * 1024);
        assertThat(Files.exists(remoteHost(".enorme.bin.m2m-tmp"))).isFalse();
    }

    @Test
    void pararTudoCancelaOQueAguardaEOQueEstaRodando() throws IOException {
        for (int i = 0; i < 4; i++) {
            try (RandomAccessFile f = new RandomAccessFile(origem.resolve("g" + i + ".bin").toFile(), "rw")) {
                f.setLength(300L * 1024 * 1024);
            }
        }
        String batch = enviar(req(local, List.of(origemPath), a, "/config/" + id));
        UUID batchId = UUID.fromString(batch);
        await().atMost(Duration.ofSeconds(60)).until(() -> repo.running(t.adminId()).size() > 0);
        service.cancelAll(t.adminId());
        await().atMost(Duration.ofSeconds(60)).until(() -> repo.pendingInBatch(batchId) == 0);
        assertThat(repo.list(t.adminId(), null, batchId, 100, 0)).filteredOn(i -> i.kind().equals("FILE"))
                .allMatch(i -> i.status().equals("CANCELED"));
        // repetir: tudo que foi cancelado volta para a fila
        assertThat(service.resumeAll(t.adminId(), List.of("CANCELED"))).isPositive();
        service.cancelAll(t.adminId());
        await().atMost(Duration.ofSeconds(60)).until(() -> repo.pendingInBatch(batchId) == 0);
    }

    @Test
    void filtrosOcultamNaListagemEAplicamSoSeMarcado() throws IOException {
        write(origem, "app/src/main.txt", "m", 1_700_000_000L);
        write(origem, "app/node_modules/dep/index.js", "d", 1_700_000_000L);
        write(origem, "app/node_modules/dep/outro.js", "d", 1_700_000_000L);
        Definition nodeModules = new Definition("ANY", false, List.of(new Condition("NAME", "EQUALS", "node_modules")));

        var listagem = service.list(t.adminId(),
                new TransferService.ListRequest(local, origemPath + "/app", nodeModules, null));
        assertThat(listagem.hidden()).isEqualTo(1);
        assertThat(listagem.items()).extracting(TransferService.Entry::name).containsExactly("src");

        // sem "aplicar à transferência": tudo é transferido
        aguardar(enviar(new PlanRequest(local, List.of(origemPath + "/app"), a, "/config/" + id + "/todos", false,
                nodeModules, null, null)));
        assertThat(Files.exists(remoteHost("todos/app/node_modules/dep/index.js"))).isTrue();

        // aplicando: o plano informa quantos itens serão ignorados (a pasta + 3 descendentes)
        PlanRequest aplicado = new PlanRequest(local, List.of(origemPath + "/app"), a, "/config/" + id + "/filtrado",
                true, nodeModules, null, null);
        assertThat(service.plan(t.adminId(), aplicado).ignoredByFilter()).isEqualTo(4);
        aguardar(enviar(aplicado));
        assertThat(Files.exists(remoteHost("filtrado/app/src/main.txt"))).isTrue();
        assertThat(Files.exists(remoteHost("filtrado/app/node_modules"))).isFalse();

        // conjunto salvo e regex inválida
        long setId = service.saveFilterSet(t.adminId(), "Sem node_modules-" + id, nodeModules);
        assertThat(service.filterSets(t.adminId())).anyMatch(f -> f.id() == setId);
        assertThat(service.list(t.adminId(), new TransferService.ListRequest(local, origemPath + "/app", null, setId))
                .hidden()).isEqualTo(1);
        service.deleteFilterSet(t.adminId(), setId);
        assertThatThrownBy(() -> service.list(t.adminId(), new TransferService.ListRequest(local, origemPath,
                new Definition("ALL", false, List.of(new Condition("NAME", "REGEX", "(["))), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("regex-invalida");
    }

    @Test
    void comparaPastasPorTamanhoEPorDataERecusaFiltrosDiferentes() throws IOException {
        write(origem, "s/so-aqui.txt", "x", 1_700_000_000L);
        write(origem, "s/igual.txt", "igual", 1_700_000_000L);
        write(origem, "s/maior.txt", "maior-aqui", 1_700_000_000L);
        write(origem, "s/novo.txt", "mesmo", 1_700_000_900L);
        write(homeA.resolve(id), "s/so-la.txt", "y", 1_700_000_000L);
        write(homeA.resolve(id), "s/igual.txt", "igual", 1_700_000_000L);
        write(homeA.resolve(id), "s/maior.txt", "menor", 1_700_000_000L);
        write(homeA.resolve(id), "s/novo.txt", "mesmo", 1_700_000_100L);
        var l = new TransferService.Side(local, origemPath + "/s", null);
        var r = new TransferService.Side(a, remote("s"), null);

        var porTamanho = service.compare(t.adminId(), new TransferService.CompareRequest(l, r, "SIZE", null));
        Map<String, String> left = new HashMap<>();
        porTamanho.entries().forEach(e -> left.put(e.name(), e.leftState()));
        assertThat(left).containsEntry("so-aqui.txt", "ONLY_HERE").containsEntry("so-la.txt", "ABSENT")
                .containsEntry("igual.txt", "SAME").containsEntry("maior.txt", "SIZE_DIFFERENT")
                .containsEntry("novo.txt", "SAME");

        var porData = service.compare(t.adminId(), new TransferService.CompareRequest(l, r, "MTIME", null));
        Map<String, String> byDate = new HashMap<>();
        porData.entries().forEach(e -> byDate.put(e.name(), e.leftState()));
        assertThat(byDate).containsEntry("novo.txt", "NEWER").containsEntry("igual.txt", "SAME");

        var comFiltro = new TransferService.Side(local, origemPath + "/s",
                new Definition("ANY", false, List.of(new Condition("NAME", "CONTAINS", "x"))));
        assertThatThrownBy(() -> service.compare(t.adminId(), new TransferService.CompareRequest(comFiltro, r, "SIZE", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("filtros-diferentes");
    }

    @Test
    void operacoesDeArquivoSaoAuditadasEExclusaoInformaAContagem() throws IOException {
        service.mkdir(t.adminId(), a, remote("novo-dir"));
        assertThat(Files.isDirectory(remoteHost("novo-dir"))).isTrue();
        write(homeA.resolve(id), "novo-dir/f1.txt", "1", 1_700_000_000L);
        write(homeA.resolve(id), "novo-dir/s/f2.txt", "2", 1_700_000_000L);

        service.rename(t.adminId(), a, remote("novo-dir"), remote("renomeado"));
        assertThat(Files.isDirectory(remoteHost("renomeado"))).isTrue();
        assertThatThrownBy(() -> service.mkdir(t.adminId(), a, remote("renomeado"))).isInstanceOf(ApiException.class)
                .hasMessageContaining("destino-existe");

        assertThat(service.countItems(t.adminId(), a, remote("renomeado"))).isEqualTo(4); // dir, f1, s, f2
        assertThatThrownBy(() -> service.delete(t.adminId(), a, remote("renomeado"), false))
                .isInstanceOf(ApiException.class).hasMessageContaining("sem-confirmacao");
        assertThat(service.delete(t.adminId(), a, remote("renomeado"), true)).isEqualTo(4);
        assertThat(Files.exists(remoteHost("renomeado"))).isFalse();

        assertThat(service.operations(t.adminId(), 50)).extracting(TransferItem::op).contains("MKDIR", "RENAME", "DELETE");
    }

    @Test
    void bloqueiaDestinoDentroDeSincronizacaoEmAndamento() throws IOException {
        var sb = t.novoSandbox();
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("Guarda-" + id, null, local,
                List.of(new ProfileDtos.Dir(sb.origemPath(),
                        List.of(new ProfileDtos.Mapping(a, "/config/" + id + "/espelho")))),
                new ProfileDtos.Storage(local, sb.armazenamentoPath()), 0.5, false, null)).id();
        long jobId = jobRepo.insert(t.adminId(), "SYNC", "MANUAL", perfil, "Guarda-" + id,
                "{\"profileId\":" + perfil + ",\"destMachineId\":" + a + "}");
        jobRepo.claim(jobId);
        try {
            write(origem, "f.txt", "f", 1_700_000_000L);
            assertThatThrownBy(() -> service.plan(t.adminId(),
                    req(local, List.of(origemPath + "/f.txt"), a, "/config/" + id + "/espelho/app")))
                    .isInstanceOf(ApiException.class).hasMessageContaining("destino-em-uso");
            assertThatThrownBy(() -> service.mkdir(t.adminId(), a, remote("espelho/novo")))
                    .isInstanceOf(ApiException.class).hasMessageContaining("destino-em-uso");
            // fora do espelho é liberado
            assertThat(service.plan(t.adminId(), req(local, List.of(origemPath + "/f.txt"), a, "/config/" + id + "/livre"))
                    .items()).isEqualTo(1);
        } finally {
            jobRepo.finish(jobId, "COMPLETED", "{}", "{}");
        }
    }

    @Test
    void reinicioMarcaInterrompidosEMantemOsAguardandoNaFila() throws IOException {
        write(origem, "r.txt", "reinicio", 1_700_000_000L);
        UUID batch = UUID.randomUUID();
        // um item que estava transferindo quando a aplicação caiu
        long rodando = repo.insert(new TransferRepository.NewItem(t.adminId(), batch, "COPY", "FILE", "QUEUED", local,
                "HP", origemPath + "/r.txt", a, "TA", remote("rodando.txt"), 8, 1_700_000_000_000L, 0644, null, false,
                null, null));
        repo.claim(rodando);
        // um item aguardando cuja mensagem se perdeu (sem registro pendente no outbox)
        long aguardando = repo.insert(new TransferRepository.NewItem(t.adminId(), batch, "COPY", "FILE", "QUEUED",
                local, "HP", origemPath + "/r.txt", a, "TA", remote("aguardando.txt"), 8, 1_700_000_000_000L, 0644,
                null, false, null, null));

        recovery.recuperar();

        assertThat(repo.findAny(rodando).orElseThrow().status()).isEqualTo("INTERRUPTED");
        TransferItem fim = esperarItem(aguardando);
        assertThat(fim.status()).isEqualTo("COMPLETED");
        assertThat(Files.readString(remoteHost("aguardando.txt"))).isEqualTo("reinicio");
    }
}
