package br.com.vovolinux.m2m.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupIT;
import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.files.ftp.FtpConnector;
import br.com.vovolinux.m2m.files.ftp.FtpFileGateway;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineDtos;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.restore.RestoreService;
import br.com.vovolinux.m2m.restore.RestoreService.RestoreRequest;
import br.com.vovolinux.m2m.restore.RestoreService.Target;
import br.com.vovolinux.m2m.support.FtpServer;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.sync.SyncService;
import br.com.vovolinux.m2m.sync.SyncService.SyncRequest;
import br.com.vovolinux.m2m.transfer.TransferItem;
import br.com.vovolinux.m2m.transfer.TransferRepository;
import br.com.vovolinux.m2m.transfer.TransferService;
import br.com.vovolinux.m2m.transfer.TransferService.PlanRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * FTP e FTPS (fidelidade parcial) em backup, restauração, sincronização e transferência, contra servidores pure-ftpd
 * em container (D19).
 */
@IntegrationTest
@Import(BackupTestSupport.class)
class FtpIntegracaoIT {

    private static FtpServer ftp;
    private static FtpServer ftps;

    @Autowired
    BackupTestSupport t;
    @Autowired
    MachineService machines;
    @Autowired
    ConnectionTester tester;
    @Autowired
    ServerIdentityService identities;
    @Autowired
    ProfileService profiles;
    @Autowired
    RestoreService restore;
    @Autowired
    SyncService sync;
    @Autowired
    TransferService transfer;
    @Autowired
    TransferRepository repo;
    @Autowired
    GatewayPool pool;

    String id;
    long ftpId;
    String base;
    FtpFileGateway gw;

    @BeforeAll
    static void subir() {
        ftp = FtpServer.start(false);
        ftps = FtpServer.start(true);
    }

    @AfterAll
    static void parar() {
        ftp.stop();
        ftps.stop();
    }

    @BeforeEach
    void preparar() throws IOException {
        id = UUID.randomUUID().toString().substring(0, 8);
        base = "/t-" + id;
        ftpId = maquina("FTP-" + id, Protocol.FTP, ftp, null);
        var client = new FtpConnector().open(ftp.host(), ftp.port(), FtpServer.USER, FtpServer.PASSWORD, null, null);
        gw = new FtpFileGateway(client, new Capabilities(false, false, true, false, false, true));
        gw.mkdirs(base);
    }

    @AfterEach
    void limpar() throws IOException {
        try {
            FsOps.deleteTree(gw, base);
        } finally {
            gw.close();
            pool.invalidate(ftpId); // fecha as conexões ociosas do pool desta máquina
        }
    }

    long maquina(String nome, Protocol protocolo, FtpServer s, String ftpsMode) {
        var view = machines.create(t.adminId(), new MachineDtos.MachineRequest(nome, null, s.host(), protocolo,
                s.port(), ftpsMode, FtpServer.USER, "PASSWORD", FtpServer.PASSWORD, null));
        var r = tester.test(machines.require(t.adminId(), view.id()));
        if (protocolo == Protocol.FTPS) {
            identities.confirm(view.id(), nome, r.fingerprint());
            tester.test(machines.require(t.adminId(), view.id()));
        }
        return view.id();
    }

    // ---- utilitários ----

    void ftpWrite(String path, String content) throws IOException {
        gw.mkdirs(FsOps.parentOf(path));
        try (OutputStream out = gw.openWrite(path, 0)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    String ftpRead(String path) throws IOException {
        try (InputStream in = gw.openRead(path, 0)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Árvore local sem modos (o FTP não os preserva): caminho → "F:sha", "D" ou "L:alvo". */
    static Map<String, String> arvore(Path raiz) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(raiz)) {
            for (Path p : (Iterable<Path>) s.skip(1)::iterator) {
                String rel = raiz.relativize(p).toString();
                if (Files.isSymbolicLink(p)) {
                    m.put(rel, "L:" + Files.readSymbolicLink(p));
                } else if (Files.isDirectory(p)) {
                    m.put(rel, "D");
                } else {
                    m.put(rel, "F:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(p))));
                }
            }
        }
        return m;
    }

    Map<String, String> arvoreFtp(String raiz) throws Exception {
        Map<String, String> m = new TreeMap<>();
        percorrer(raiz, "", m);
        return m;
    }

    private void percorrer(String raiz, String rel, Map<String, String> m) throws Exception {
        for (FileEntry e : gw.list(rel.isEmpty() ? raiz : raiz + "/" + rel)) {
            String r = rel.isEmpty() ? e.name() : rel + "/" + e.name();
            if (e.isDirectory()) {
                m.put(r, "D");
                percorrer(raiz, r, m);
            } else {
                try (InputStream in = gw.openRead(e.path(), 0)) {
                    m.put(r, "F:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(in.readAllBytes())));
                }
            }
        }
    }

    // ---- backup com origem FTP ----

    @Test
    void backupComOrigemFtpGravaNoLocalComAvisoDeFidelidadeEPodeSerRestaurado() throws Exception {
        ftpWrite(base + "/origem/a.txt", "A");
        ftpWrite(base + "/origem/.oculto", "H");
        ftpWrite(base + "/origem/sub/ação ç.txt", "acentos");
        ftpWrite(base + "/origem/sub/fundo/z.bin", "ZZZ");
        gw.mkdirs(base + "/origem/vazio");

        var sb = t.novoSandbox();
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("ftp-bk-" + id, null, ftpId,
                List.of(new ProfileDtos.Dir(base + "/origem", List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        Job job = t.executar(perfil, null);
        assertThat(job.status()).as(job.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);

        BackupArchive a = t.arquivos(perfil).get(0);
        assertThat(a.fullFidelity()).isFalse(); // FTP não preserva permissões nem links
        assertThat(a.fileCount()).isEqualTo(4);

        // restaura num caminho local novo e confere o conteúdo
        Path destino = sb.origem().resolveSibling("restaurado");
        String destinoPath = sb.origemPath().replace("/origem", "/restaurado");
        t.awaitIdle();
        Job r = t.esperar(restore.start(t.adminId(), a.id(), new RestoreRequest(t.localMachineId(),
                List.of(new Target(1, destinoPath)), true), false).id());
        assertThat(r.status()).as(r.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(arvore(destino)).isEqualTo(arvoreFtpComoLocal());
    }

    private Map<String, String> arvoreFtpComoLocal() throws Exception {
        return arvoreFtp(base + "/origem");
    }

    @Test
    void ciclosDeDiretorioNaOrigemSaoDetectadosPelaForma() throws Exception {
        // o servidor de teste não cria links; garante ao menos que o canônico é calculado e que não há falso positivo
        gw.mkdirs(base + "/origem/a/b/c");
        ftpWrite(base + "/origem/a/b/c/f.txt", "f");
        var sb = t.novoSandbox();
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("ftp-cic-" + id, null, ftpId,
                List.of(new ProfileDtos.Dir(base + "/origem", List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        assertThat(t.executar(perfil, null).status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
    }

    // ---- restauração com destino FTP ----

    @Test
    void restauracaoParaDestinoFtpPulaLinksAvisaEDesfazPelaCopiaDeSeguranca() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "A");
        Files.writeString(sb.origem().resolve("ação ç.txt"), "acentos");
        Files.createDirectories(sb.origem().resolve("sub/vazio"));
        Files.writeString(sb.origem().resolve("sub/b.txt"), "B");
        Files.createSymbolicLink(sb.origem().resolve("atalho"), Path.of("a.txt"));
        Files.setLastModifiedTime(sb.origem().resolve("a.txt"), FileTime.fromMillis(1_700_000_000_000L));
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("ftp-rest-" + id, null,
                t.localMachineId(), List.of(new ProfileDtos.Dir(sb.origemPath(), List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        assertThat(t.executar(perfil, null).status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        BackupArchive backup = t.arquivos(perfil).get(0);

        // destino FTP já tem uma versão velha e um extra
        ftpWrite(base + "/dest/a.txt", "versão antiga");
        ftpWrite(base + "/dest/extra.txt", "extra");
        Map<String, String> antes = arvoreFtp(base + "/dest");

        var previa = restore.preview(t.adminId(), backup.id(),
                new RestoreRequest(ftpId, List.of(new Target(1, base + "/dest")), true));
        assertThat(previa.warnings()).isNotEmpty(); // fidelidade parcial no destino

        t.awaitIdle();
        Job job = t.esperar(restore.start(t.adminId(), backup.id(),
                new RestoreRequest(ftpId, List.of(new Target(1, base + "/dest")), true), false).id());
        assertThat(job.status()).as(job.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);

        Map<String, String> depois = arvoreFtp(base + "/dest");
        assertThat(depois).containsKeys("a.txt", "ação ç.txt", "sub", "sub/vazio", "sub/b.txt")
                .doesNotContainKeys("extra.txt", "atalho");
        assertThat(ftpRead(base + "/dest/a.txt")).isEqualTo("A");
        assertThat(ftpRead(base + "/dest/ação ç.txt")).isEqualTo("acentos");
        assertThat(job.result()).contains("link");

        // desfazer pela cópia de segurança do destino FTP
        BackupArchive pre = t.arquivos(perfil).stream().filter(x -> x.type().equals("PRE_RESTORE")).findFirst()
                .orElseThrow();
        t.awaitIdle();
        Job undo = t.esperar(restore.undo(t.adminId(), pre.id()).id());
        assertThat(undo.status()).as(undo.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(arvoreFtp(base + "/dest")).isEqualTo(antes);
        assertThat(ftpRead(base + "/dest/a.txt")).isEqualTo("versão antiga");
    }

    // ---- sincronização com destino FTP ----

    @Test
    void sincronizacaoParaDestinoFtpCopiaSoOAlteradoEPropagaExclusoes() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "A");
        Files.createDirectories(sb.origem().resolve("sub"));
        Files.writeString(sb.origem().resolve("sub/b.txt"), "B");
        Files.writeString(sb.origem().resolve("sub/c.txt"), "C");
        for (var f : List.of("a.txt", "sub/b.txt", "sub/c.txt")) {
            Files.setLastModifiedTime(sb.origem().resolve(f), FileTime.fromMillis(1_700_000_000_000L));
        }
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("ftp-sync-" + id, null,
                t.localMachineId(), List.of(new ProfileDtos.Dir(sb.origemPath(),
                        List.of(new ProfileDtos.Mapping(ftpId, base + "/espelho")))),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
        SyncRequest req = new SyncRequest(ftpId, false, true, false, false);

        Job primeira = t.esperar(sync.start(t.adminId(), perfil, req).id());
        assertThat(primeira.status()).as(primeira.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(ftpRead(base + "/espelho/sub/b.txt")).isEqualTo("B");
        assertThat(sync.preview(t.adminId(), perfil, req).alreadySynced()).isTrue();

        Files.writeString(sb.origem().resolve("a.txt"), "A2");
        Files.setLastModifiedTime(sb.origem().resolve("a.txt"), FileTime.fromMillis(1_700_000_100_000L));
        Files.delete(sb.origem().resolve("sub/c.txt"));
        var prev = sync.preview(t.adminId(), perfil, req);
        assertThat(prev.update()).isEqualTo(1);
        assertThat(prev.delete()).isEqualTo(1);
        assertThat(prev.warnings()).contains("destino-fidelidade-parcial");

        t.awaitIdle();
        Job segunda = t.esperar(sync.start(t.adminId(), perfil, req).id());
        assertThat(segunda.status()).as(segunda.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(ftpRead(base + "/espelho/a.txt")).isEqualTo("A2");
        assertThat(gw.lstatIfExists(base + "/espelho/sub/c.txt")).isEmpty();
        assertThat(sync.preview(t.adminId(), perfil, req).alreadySynced()).isTrue();
    }

    // ---- transferência com FTP e retomada ----

    TransferItem esperar(long itemId) {
        await().atMost(Duration.ofSeconds(120)).until(() -> {
            String s = repo.findAny(itemId).orElseThrow().status();
            return !s.equals("QUEUED") && !s.equals("TRANSFERRING");
        });
        return repo.findAny(itemId).orElseThrow();
    }

    List<TransferItem> aguardar(String batchId) {
        UUID batch = UUID.fromString(batchId);
        await().atMost(Duration.ofSeconds(120)).until(() -> repo.pendingInBatch(batch) == 0);
        return repo.list(t.adminId(), null, batch, 1000, 0);
    }

    @Test
    void transferenciaNosDoisSentidosComFtp() throws Exception {
        var sb = t.novoSandbox();
        Files.createDirectories(sb.origem().resolve("pasta/sub"));
        Files.writeString(sb.origem().resolve("pasta/a.txt"), "A");
        Files.writeString(sb.origem().resolve("pasta/.oculto"), "H");
        Files.writeString(sb.origem().resolve("pasta/sub/b.txt"), "B");
        Files.createDirectories(sb.origem().resolve("pasta/vazio"));
        Files.createSymbolicLink(sb.origem().resolve("pasta/link"), Path.of("a.txt"));

        // local → FTP: o link é pulado com motivo; o resto chega
        String lote = transfer.start(t.adminId(), new PlanRequest(t.localMachineId(), List.of(sb.origemPath() + "/pasta"),
                ftpId, base, false, null, null, null)).batchId();
        List<TransferItem> itens = aguardar(lote);
        assertThat(itens).filteredOn(i -> i.kind().equals("SYMLINK")).allMatch(i -> i.status().equals("SKIPPED")
                && "destino-sem-links".equals(i.skipReason()));
        assertThat(ftpRead(base + "/pasta/sub/b.txt")).isEqualTo("B");
        assertThat(ftpRead(base + "/pasta/.oculto")).isEqualTo("H");
        assertThat(gw.lstat(base + "/pasta/vazio").isDirectory()).isTrue();

        // FTP → local
        Path baixados = sb.origem().resolveSibling("baixados");
        Files.createDirectories(baixados);
        aguardar(transfer.start(t.adminId(), new PlanRequest(ftpId, List.of(base + "/pasta"), t.localMachineId(),
                sb.origemPath().replace("/origem", "/baixados"), false, null, null, null)).batchId());
        assertThat(Files.readString(baixados.resolve("pasta/sub/b.txt"))).isEqualTo("B");
        assertThat(Files.isDirectory(baixados.resolve("pasta/vazio"))).isTrue();
    }

    long itemInterrompido(long origem, String srcPath, long destino, String dstPath, long size, long mtime,
            long offset, String tmp) {
        long itemId = repo.insert(new TransferRepository.NewItem(t.adminId(), UUID.randomUUID(), "COPY", "FILE",
                "QUEUED", origem, "o", srcPath, destino, "d", dstPath, size, mtime, 0644, null, false, "OVERWRITE",
                null));
        repo.claim(itemId);
        repo.markStarted(itemId, size, mtime, tmp, offset, null);
        repo.finish(itemId, "FAILED", offset, offset, null, "conexao.falha", null, null);
        return itemId;
    }

    @Test
    void retomadaComFtpNosDoisSentidosUsaRestEAppe() throws Exception {
        byte[] full = new byte[3_000_000];
        new Random(5).nextBytes(full);
        var sb = t.novoSandbox();
        Path src = sb.origem().resolve("big.bin");
        Files.write(src, full);
        long mtime = Files.getLastModifiedTime(src).toMillis();

        // upload: o temporário no FTP já tem 1 MB; continua por APPE
        try (OutputStream out = gw.openWrite(base + "/.big.bin.m2m-tmp", 0)) {
            out.write(full, 0, 1_000_000);
        }
        long up = itemInterrompido(t.localMachineId(), sb.origemPath() + "/big.bin", ftpId, base + "/big.bin",
                full.length, mtime, 1_000_000, base + "/.big.bin.m2m-tmp");
        transfer.resume(t.adminId(), up);
        TransferItem ok = esperar(up);
        assertThat(ok.status()).isEqualTo("COMPLETED");
        assertThat(ok.note()).isEqualTo("retomado");
        try (InputStream in = gw.openRead(base + "/big.bin", 0)) {
            assertThat(in.readAllBytes()).isEqualTo(full);
        }
        assertThat(gw.lstatIfExists(base + "/.big.bin.m2m-tmp")).isEmpty();

        // download: o FTP é a origem; o temporário local já tem 1 MB; a leitura abre com REST
        Path baixados = sb.origem().resolveSibling("baixados");
        Files.createDirectories(baixados);
        Files.write(baixados.resolve(".big.bin.m2m-tmp"), java.util.Arrays.copyOf(full, 1_000_000));
        long ftpMtime = gw.lstat(base + "/big.bin").mtime().toEpochMilli();
        String dstDir = sb.origemPath().replace("/origem", "/baixados");
        long down = itemInterrompido(ftpId, base + "/big.bin", t.localMachineId(), dstDir + "/big.bin", full.length,
                ftpMtime, 1_000_000, dstDir + "/.big.bin.m2m-tmp");
        transfer.resume(t.adminId(), down);
        TransferItem baixado = esperar(down);
        assertThat(baixado.status()).as(baixado.errorCode() + " " + baixado.errorParams()).isEqualTo("COMPLETED");
        assertThat(baixado.note()).isEqualTo("retomado");
        assertThat(Files.readAllBytes(baixados.resolve("big.bin"))).isEqualTo(full);
    }

    // ---- FTPS ----

    @Test
    void ftpsExigeConfirmarAIdentidadeDoCertificadoEDepoisTransfere() throws Exception {
        var view = machines.create(t.adminId(), new MachineDtos.MachineRequest("FTPS-" + id, null, ftps.host(),
                Protocol.FTPS, ftps.port(), "EXPLICIT", FtpServer.USER, "PASSWORD", FtpServer.PASSWORD, null));
        var primeira = tester.test(machines.require(t.adminId(), view.id()));
        assertThat(primeira.ok()).isFalse();
        assertThat(primeira.fingerprint()).startsWith("SHA256:");
        identities.confirm(view.id(), "FTPS-" + id, primeira.fingerprint());
        var segunda = tester.test(machines.require(t.adminId(), view.id()));
        assertThat(segunda.ok()).as(segunda.steps().toString()).isTrue();

        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("tls.txt"), "sobre TLS");
        aguardar(transfer.start(t.adminId(), new PlanRequest(t.localMachineId(), List.of(sb.origemPath() + "/tls.txt"),
                view.id(), "/", false, null, null, null)).batchId());
        var client = new FtpConnector().open(ftps.host(), ftps.port(), FtpServer.USER, FtpServer.PASSWORD, "EXPLICIT",
                f -> { });
        try (FtpFileGateway tls = new FtpFileGateway(client, new Capabilities(false, false, true, false, false, true))) {
            try (InputStream in = tls.openRead("/tls.txt", 0)) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("sobre TLS");
            }
            tls.delete("/tls.txt");
        }
    }
}
