package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.crypto.ArchiveCrypto;
import br.com.vovolinux.m2m.crypto.DecryptCli;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.restore.RestoreService;
import br.com.vovolinux.m2m.restore.RestoreService.RestoreRequest;
import br.com.vovolinux.m2m.restore.RestoreService.Target;
import br.com.vovolinux.m2m.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Backups criptografados com Tink: gravar, validar, restaurar, verificar, reindexar e decifrar fora do m2m. */
@IntegrationTest
@Import(BackupTestSupport.class)
class CriptografiaIT {

    private static final String SEGREDO = "SEGREDO-QUE-NAO-PODE-APARECER-EM-CLARO";

    @Autowired
    BackupTestSupport t;
    @Autowired
    ProfileService profiles;
    @Autowired
    RestoreService restore;
    @Autowired
    JobService jobs;
    @Autowired
    ArchiveCrypto crypto;
    @Autowired
    BackupService backupService;
    @Autowired
    JdbcClient jdbc;

    record Cenario(BackupTestSupport.Sandbox sb, long perfil, BackupArchive backup) {
    }

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
                    m.put(rel, "F:" + new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).hashCode() + ":"
                            + Files.size(p));
                }
            }
        }
        return m;
    }

    Cenario cenario() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("segredo.txt"), SEGREDO);
        Files.createDirectories(sb.origem().resolve("sub/vazio"));
        Files.writeString(sb.origem().resolve("sub/b.txt"), "conteúdo B");
        Files.createSymbolicLink(sb.origem().resolve("atalho"), Path.of("segredo.txt"));
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("cripto-" + sb.id(), null,
                t.localMachineId(), List.of(new ProfileDtos.Dir(sb.origemPath(), List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, true, null)).id();
        Job job = t.executar(perfil, null);
        assertThat(job.status()).as(job.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        return new Cenario(sb, perfil, t.arquivos(perfil).get(0));
    }

    @Test
    void gravaCifradoValidaERestauraComCopiaDeSegurancaTambemCifrada() throws Exception {
        Cenario c = cenario();
        BackupArchive a = c.backup();
        assertThat(a.encrypted()).isTrue();
        assertThat(a.keyId()).isNotBlank();
        assertThat(a.fileName()).endsWith(".tar.zst.enc");
        Path arquivo = c.sb().armazenamento().resolve(a.fileName());
        Path lateral = c.sb().armazenamento().resolve(a.fileName() + ".manifest");
        assertThat(Files.exists(arquivo)).isTrue();
        assertThat(Files.exists(lateral)).isTrue();

        // nada em claro: nem o conteúdo, nem nomes de arquivo, nem o manifesto lateral
        for (Path p : List.of(arquivo, lateral)) {
            String bruto = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            assertThat(bruto).doesNotContain(SEGREDO).doesNotContain("segredo.txt").doesNotContain("sub/b.txt");
        }
        // o conteúdo do backup continua navegável pelo m2m (manifesto lateral decifrado)
        var raiz = backupService.tree(t.adminId(), a.id(), "", 0, 50);
        assertThat(raiz.items()).isNotEmpty();

        // restauração: destino com versão antiga para forçar a cópia de segurança
        String destino = c.sb().origemPath().replace("/origem", "/restaurado");
        Path destinoHost = c.sb().origem().resolveSibling("restaurado");
        Files.createDirectories(destinoHost);
        Files.writeString(destinoHost.resolve("segredo.txt"), "versão antiga");
        t.awaitIdle();
        Job r = t.esperar(restore.start(t.adminId(), a.id(), new RestoreRequest(t.localMachineId(),
                List.of(new Target(1, destino)), true), false).id());
        assertThat(r.status()).as(r.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(arvore(destinoHost)).isEqualTo(arvore(c.sb().origem()));

        BackupArchive pre = t.arquivos(c.perfil()).stream().filter(x -> x.type().equals("PRE_RESTORE")).findFirst()
                .orElseThrow();
        assertThat(pre.encrypted()).isTrue();
        assertThat(pre.fileName()).endsWith(".tar.zst.enc");
        assertThat(new String(Files.readAllBytes(c.sb().armazenamento().resolve(pre.fileName())),
                StandardCharsets.ISO_8859_1)).doesNotContain("versão antiga");

        // desfazer lê a cópia de segurança cifrada
        t.awaitIdle();
        Job undo = t.esperar(restore.undo(t.adminId(), pre.id()).id());
        assertThat(undo.status()).as(undo.result()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(Files.readString(destinoHost.resolve("segredo.txt"))).isEqualTo("versão antiga");
    }

    @Test
    void verificacaoPeriodicaDetectaAdulteracaoPelaAutenticacaoDaCifra() throws Exception {
        Cenario c = cenario();
        t.awaitIdle();
        // a verificação cobre os backups de todo o usuário (outros testes deixam arquivos adulterados): confere o deste
        Job ok = t.esperar(jobs.enqueue(t.adminId(), JobType.ARCHIVE_VERIFY, "MANUAL", null, null, Map.of()).id());
        assertThat(ok.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(t.arquivos(c.perfil()).get(0).status()).isEqualTo(ArchiveStatus.COMPLETED);

        Path arquivo = c.sb().armazenamento().resolve(c.backup().fileName());
        try (RandomAccessFile f = new RandomAccessFile(arquivo.toFile(), "rw")) {
            long meio = f.length() / 2;
            f.seek(meio);
            int b = f.read();
            f.seek(meio);
            f.write(b ^ 0x01);
        }
        t.awaitIdle();
        Job ruim = t.esperar(jobs.enqueue(t.adminId(), JobType.ARCHIVE_VERIFY, "MANUAL", null, null, Map.of()).id());
        assertThat(ruim.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(t.arquivos(c.perfil()).get(0).status()).isEqualTo(ArchiveStatus.CORRUPTED);
    }

    @Test
    void decifraForaDoM2mComOArquivoDaChaveEOComandoDecrypt() throws Exception {
        Cenario c = cenario();
        Path trabalho = c.sb().armazenamento().resolveSibling("fora");
        Files.createDirectories(trabalho);
        Path chave = trabalho.resolve("chave.json");
        Files.writeString(chave, crypto.exportKey(t.adminId(), c.perfil()).orElseThrow());
        assertThat(Files.readString(chave)).contains("m2m-chave-de-perfil").contains(c.backup().keyId());

        Path claro = trabalho.resolve("backup.tar.zst");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DecryptCli.run(new String[] {"decrypt", "--key", chave.toString(), "--in",
                c.sb().armazenamento().resolve(c.backup().fileName()).toString(), "--out", claro.toString()},
                new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString()).isZero();
        Path extraido = trabalho.resolve("extraido");
        BackupIT.extrairComTar(claro, extraido);
        assertThat(arvore(extraido.resolve("dir-1"))).isEqualTo(arvore(c.sb().origem()));

        // chave de outro perfil não decifra, e nada fica pela metade
        Cenario outro = cenario();
        Path chaveErrada = trabalho.resolve("errada.json");
        Files.writeString(chaveErrada, crypto.exportKey(t.adminId(), outro.perfil()).orElseThrow());
        Path saida = trabalho.resolve("nao.tar.zst");
        int falha = DecryptCli.run(new String[] {"decrypt", "--key", chaveErrada.toString(), "--in",
                c.sb().armazenamento().resolve(c.backup().fileName()).toString(), "--out", saida.toString()},
                new PrintStream(out), new PrintStream(err));
        assertThat(falha).isEqualTo(2);
        assertThat(Files.exists(saida)).isFalse();
        assertThat(Files.exists(trabalho.resolve("nao.tar.zst.partial"))).isFalse();
        assertThat(DecryptCli.run(new String[] {"decrypt"}, new PrintStream(out), new PrintStream(err))).isEqualTo(1);
    }

    @Test
    void reindexaBackupCifradoComAChaveDoUsuarioESemAChaveAvisa() throws Exception {
        Cenario c = cenario();
        jdbc.sql("delete from backup_archive where id = :id").param("id", c.backup().id()).update();
        Map<String, Object> params = Map.of("machineId", t.localMachineId(), "dir", c.sb().armazenamentoPath());
        t.awaitIdle();
        Job j = t.esperar(jobs.enqueue(t.adminId(), JobType.REINDEX, "MANUAL", null, null, params).id());
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        BackupArchive r = t.arquivos(c.perfil()).get(0);
        assertThat(r.encrypted()).isTrue();
        assertThat(r.keyId()).isEqualTo(c.backup().keyId());
        assertThat(r.fileCount()).isEqualTo(c.backup().fileCount());
    }

    @Test
    void semAChaveORestauracaoFalhaComMensagemClaraENadaEAlterado() throws Exception {
        Cenario c = cenario();
        // a chave some (ex.: banco restaurado de outra instalação): o backup não pode ser lido
        jdbc.sql("update backup_archive set key_id = 'chave-inexistente' where id = :id").param("id", c.backup().id())
                .update();
        String destino = c.sb().origemPath().replace("/origem", "/sem-chave");
        t.awaitIdle();
        // a restauração é recusada já na abertura, com o código da chave ausente, e nada é alterado
        assertThatThrownBy(() -> restore.start(t.adminId(), c.backup().id(), new RestoreRequest(t.localMachineId(),
                List.of(new Target(1, destino)), true), false)).isInstanceOf(br.com.vovolinux.m2m.web.ApiException.class)
                .extracting(e -> ((br.com.vovolinux.m2m.web.ApiException) e).codigo()).isEqualTo("backup.chave-ausente");
        assertThat(Files.exists(c.sb().origem().resolveSibling("sem-chave"))).isFalse();
        assertThatThrownBy(() -> crypto.byId("chave-inexistente")).isInstanceOf(ArchiveCrypto.KeyUnavailable.class);
    }

    @Test
    void perfilSemCriptografiaContinuaGravandoEmClaro() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), SEGREDO);
        long perfil = t.criarPerfil(sb, "claro-" + UUID.randomUUID().toString().substring(0, 6));
        Job j = t.executar(perfil, null);
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        BackupArchive a = t.arquivos(perfil).get(0);
        assertThat(a.encrypted()).isFalse();
        assertThat(a.keyId()).isNull();
        assertThat(a.fileName()).endsWith(".tar.zst");
    }
}
