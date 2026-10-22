package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(BackupTestSupport.class)
public class BackupIT {

    @Autowired
    BackupTestSupport t;

    private static String nome() {
        return "bk-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Monta uma árvore com tudo que o backup precisa preservar. */
    public static void montarOrigem(Path o) throws Exception {
        Files.writeString(o.resolve(".env"), "SEGREDO=1\n");
        Files.createDirectories(o.resolve(".git"));
        Files.writeString(o.resolve(".git/config"), "[core]\n");
        Files.writeString(o.resolve("arquivo.tmp"), "temporario");
        Files.writeString(o.resolve("~lock.odt#"), "lock");
        Files.createDirectories(o.resolve("pasta vazia"));
        Files.createFile(o.resolve("zero-bytes.bin"));
        Files.writeString(o.resolve("build.sh"), "#!/bin/sh\necho oi\n");
        Files.setPosixFilePermissions(o.resolve("build.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(o.resolve("v2"));
        Files.writeString(o.resolve("v2/ok.txt"), "conteudo de v2");
        Files.createSymbolicLink(o.resolve("atual"), Path.of("v2"));
        Files.createSymbolicLink(o.resolve("quebrado"), Path.of("nao-existe"));
        Files.writeString(o.resolve("original.txt"), "hard link");
        Files.createLink(o.resolve("hardlink.txt"), o.resolve("original.txt"));
        Files.writeString(o.resolve("ação ç.txt"), "acentos");
        Files.writeString(o.resolve("com espaço.txt"), "espaço");
        Files.writeString(o.resolve("quebra\nde-linha.txt"), "newline");
        Files.setLastModifiedTime(o.resolve("build.sh"), FileTime.from(Instant.parse("2021-03-04T05:06:07Z")));
    }

    public static void criarEspeciais(Path o) throws Exception {
        new ProcessBuilder("mkfifo", o.resolve("fila").toString()).inheritIO().start().waitFor();
        new ProcessBuilder("python3", "-c", "import socket,sys;s=socket.socket(socket.AF_UNIX);s.bind(sys.argv[1])",
                o.resolve("app.sock").toString()).inheritIO().start().waitFor();
    }

    public static void extrairComTar(Path arquivo, Path destino) throws Exception {
        Files.createDirectories(destino);
        Process p = new ProcessBuilder("tar", "--zstd", "-xpf", arquivo.toString(), "-C", destino.toString())
                .redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("tar: " + saida).isZero();
    }

    @Test
    void backupCompletoPreservaTudoEPodeSerExtraidoComTar() throws Exception {
        var sb = t.novoSandbox();
        montarOrigem(sb.origem());
        criarEspeciais(sb.origem());
        String nome = nome();
        long perfil = t.criarPerfil(sb, nome, "sprint3", 0.5, t.localMachineId(), sb.armazenamentoPath());

        Job job = t.executar(perfil, null);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).hasSize(1);
        BackupArchive a = lista.get(0);
        assertThat(a.status()).isEqualTo(ArchiveStatus.COMPLETED_WITH_WARNINGS);
        assertThat(a.accepted()).isTrue();
        assertThat(a.fileName()).matches("\\Q" + nome + "\\E_sprint3_NOTEBOOK_\\d{8}-\\d{6}\\.tar\\.zst");
        assertThat(a.sha256()).hasSize(64);
        assertThat(a.fullFidelity()).isTrue();
        assertThat(a.warnings()).contains("backup.item-especial-ignorado").contains("fila").contains("app.sock");

        Path arq = sb.armazenamento().resolve(a.fileName());
        assertThat(arq).exists();
        assertThat(sb.armazenamento().resolve(a.fileName() + ".manifest")).exists();
        try (Stream<Path> s = Files.list(sb.armazenamento())) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".partial"));
        }

        Path out = sb.armazenamento().resolveSibling("extraido");
        extrairComTar(arq, out);
        Path d = out.resolve("dir-1");
        assertThat(d.resolve(".env")).hasContent("SEGREDO=1\n");
        assertThat(d.resolve(".git/config")).exists();
        assertThat(d.resolve("arquivo.tmp")).exists();
        assertThat(d.resolve("~lock.odt#")).exists();
        assertThat(d.resolve("pasta vazia")).isDirectory();
        assertThat(d.resolve("zero-bytes.bin")).exists().hasSize(0);
        assertThat(Files.getPosixFilePermissions(d.resolve("build.sh")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getLastModifiedTime(d.resolve("build.sh")).toInstant())
                .isEqualTo(Instant.parse("2021-03-04T05:06:07Z"));
        assertThat(Files.isSymbolicLink(d.resolve("atual"))).isTrue();
        assertThat(Files.readSymbolicLink(d.resolve("atual"))).isEqualTo(Path.of("v2"));
        assertThat(Files.isSymbolicLink(d.resolve("quebrado"))).isTrue();
        assertThat(d.resolve("v2/ok.txt")).hasContent("conteudo de v2");
        assertThat(d.resolve("original.txt")).hasContent("hard link");
        assertThat(d.resolve("hardlink.txt")).hasContent("hard link");
        assertThat(Files.isSameFile(d.resolve("original.txt"), d.resolve("hardlink.txt"))).isFalse();
        assertThat(d.resolve("ação ç.txt")).hasContent("acentos");
        assertThat(d.resolve("com espaço.txt")).hasContent("espaço");
        assertThat(d.resolve("quebra\nde-linha.txt")).hasContent("newline");
        assertThat(Files.exists(d.resolve("fila"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(d.resolve("app.sock"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(out.resolve(".m2m-manifest.json")).exists();
    }

    @Test
    void complementoPontualSubstituiODoPerfil() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "a");
        long perfil = t.criarPerfil(sb, nome(), "padrao", 0.5, t.localMachineId(), sb.armazenamentoPath());
        Job job = t.executar(perfil, "antes-da-entrega");
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(t.arquivos(perfil).get(0).fileName()).contains("_antes-da-entrega_").doesNotContain("_padrao_");
    }

    @Test
    void permissaoNegadaFazOBackupFalharIdentificandoOArquivo() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("ok.txt"), "ok");
        Path secreto = sb.origem().resolve("secreto.txt");
        Files.writeString(secreto, "nao pode ler");
        long perfil = t.criarPerfil(sb, nome());
        assertThat(t.executar(perfil, null).status()).isEqualTo(JobStatus.COMPLETED);

        Files.setPosixFilePermissions(secreto, PosixFilePermissions.fromString("---------"));
        try {
            Job job = t.executar(perfil, null);
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
            assertThat(job.result()).contains("backup.erro-de-leitura").contains("secreto.txt");
        } finally {
            Files.setPosixFilePermissions(secreto, PosixFilePermissions.fromString("rw-r--r--"));
        }
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).extracting(BackupArchive::status).containsExactly(ArchiveStatus.COMPLETED,
                ArchiveStatus.FAILED);
        try (Stream<Path> s = Files.list(sb.armazenamento())) {
            List<String> nomes = s.map(p -> p.getFileName().toString()).toList();
            assertThat(nomes).noneMatch(n -> n.endsWith(".partial"));
            assertThat(nomes.stream().filter(n -> n.endsWith(".tar.zst"))).hasSize(1);
        }
    }

    @Test
    void retencaoMantemOsDoisAceitosMaisRecentes() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "a");
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "um");
        t.executar(perfil, "dois");
        assertThat(t.arquivos(perfil)).hasSize(2);
        assertThat(t.executar(perfil, "tres").status()).isEqualTo(JobStatus.COMPLETED);

        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).hasSize(2);
        assertThat(lista).extracting(BackupArchive::fileName).anyMatch(n -> n.contains("_dois_"))
                .anyMatch(n -> n.contains("_tres_")).noneMatch(n -> n.contains("_um_"));
        try (Stream<Path> s = Files.list(sb.armazenamento())) {
            List<String> nomes = s.map(p -> p.getFileName().toString()).toList();
            assertThat(nomes.stream().filter(n -> n.endsWith(".tar.zst"))).hasSize(2);
            assertThat(nomes.stream().filter(n -> n.endsWith(".manifest"))).hasSize(2);
        }
    }

    @Test
    void falhaNaoApagaOsBackupsAnterioresNemContaParaRetencao() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "a");
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "a");
        t.executar(perfil, "b");
        Files.setPosixFilePermissions(sb.origem().resolve("a.txt"), PosixFilePermissions.fromString("---------"));
        try {
            assertThat(t.executar(perfil, "c").status()).isEqualTo(JobStatus.FAILED);
        } finally {
            Files.setPosixFilePermissions(sb.origem().resolve("a.txt"), PosixFilePermissions.fromString("rw-r--r--"));
        }
        assertThat(t.arquivos(perfil)).extracting(BackupArchive::status)
                .containsExactly(ArchiveStatus.COMPLETED, ArchiveStatus.COMPLETED, ArchiveStatus.FAILED);
        try (Stream<Path> s = Files.list(sb.armazenamento())) {
            assertThat(s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tar.zst"))).hasSize(2);
        }
    }

    @Test
    void backupSuspeitoNaoAcionaARetencao() throws Exception {
        var sb = t.novoSandbox();
        for (int i = 0; i < 10; i++) {
            Files.writeString(sb.origem().resolve("f" + i + ".txt"), "conteudo " + i);
        }
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "a");
        t.executar(perfil, "b");
        for (int i = 2; i < 10; i++) {
            Files.delete(sb.origem().resolve("f" + i + ".txt"));
        }
        Job job = t.executar(perfil, "c");
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(job.result()).contains("SUSPECT");
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).hasSize(3);
        BackupArchive suspeito = lista.get(2);
        assertThat(suspeito.status()).isEqualTo(ArchiveStatus.SUSPECT);
        assertThat(suspeito.accepted()).isFalse();
        assertThat(lista.get(0).status()).isEqualTo(ArchiveStatus.COMPLETED);
        assertThat(sb.armazenamento().resolve(lista.get(0).fileName())).exists();
    }

    @Test
    void regraDeSuspeito() {
        var anterior = new BackupArchive(1, 1L, 1L, "p", "REGULAR", "COMPLETED", true, false, 1L, "m", "/d", "f", "o",
                "LOCAL", true, 100, 100, 1000, 10, null, false, "[]", "[]", null, null, null, Instant.now(), null);
        assertThat(BackupHandler.isSuspect(null, 0, 0, 0.5)).isFalse();
        assertThat(BackupHandler.isSuspect(anterior, 0, 0, 0.5)).isTrue();
        assertThat(BackupHandler.isSuspect(anterior, 49, 1000, 0.5)).isTrue();
        assertThat(BackupHandler.isSuspect(anterior, 100, 499, 0.5)).isTrue();
        assertThat(BackupHandler.isSuspect(anterior, 50, 500, 0.5)).isFalse();
        assertThat(BackupHandler.isSuspect(anterior, 30, 400, 0.3)).isFalse();
        assertThat(BackupHandler.isSuspect(anterior, 29, 400, 0.3)).isTrue();
    }
}
