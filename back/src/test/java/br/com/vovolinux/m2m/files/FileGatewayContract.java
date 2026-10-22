package br.com.vovolinux.m2m.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contrato que todo {@link FileGateway} de fidelidade total (LOCAL, SFTP) deve cumprir. */
public abstract class FileGatewayContract {

    protected abstract FileGateway gateway();

    /** Diretório limpo, no espaço de nomes do gateway, exclusivo deste teste. */
    protected abstract String root();

    /** Caminho no sistema de arquivos do host do teste correspondente a um caminho do gateway. */
    protected abstract Path hostPath(String gatewayPath);

    protected String p(String name) {
        return root() + "/" + name;
    }

    protected void write(String path, String content) throws IOException {
        try (OutputStream out = gateway().openWrite(path, 0)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    protected String read(String path) throws IOException {
        try (InputStream in = gateway().openRead(path, 0)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, FileEntry> listByName(String dir) throws IOException {
        return gateway().list(dir).stream().collect(Collectors.toMap(FileEntry::name, Function.identity()));
    }

    @Test
    void listaOcultosETemporariosEVazios() throws Exception {
        write(p(".oculto"), "a");
        write(p("rascunho.tmp"), "b");
        write(p("~$documento.docx"), "c");
        write(p("vazio.bin"), "");
        gateway().mkdirs(p(".pasta-oculta/sub"));

        Map<String, FileEntry> itens = listByName(root());
        assertThat(itens).containsKeys(".oculto", "rascunho.tmp", "~$documento.docx", "vazio.bin", ".pasta-oculta");
        assertThat(itens.get("vazio.bin").size()).isZero();
        assertThat(itens.get("vazio.bin").type()).isEqualTo(FileType.FILE);
        assertThat(itens.get(".pasta-oculta").type()).isEqualTo(FileType.DIRECTORY);
        assertThat(itens.get(".oculto").path()).isEqualTo(p(".oculto"));
    }

    @Test
    void nomesComAcentosEspacosEQuebraDeLinha() throws Exception {
        String[] nomes = {"ação ç.txt", "com espaço.txt", "quebra\nde-linha.txt", "aspas\"simples'.txt"};
        for (String n : nomes) {
            write(p(n), n);
        }
        Map<String, FileEntry> itens = listByName(root());
        for (String n : nomes) {
            assertThat(itens).containsKey(n);
            assertThat(read(p(n))).isEqualTo(n);
        }
    }

    @Test
    void leituraComDeslocamento() throws Exception {
        write(p("dados.txt"), "0123456789");
        try (InputStream in = gateway().openRead(p("dados.txt"), 4)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("456789");
        }
    }

    @Test
    void gravacaoRetomadaDeUmDeslocamento() throws Exception {
        write(p("parcial.bin"), "01234xxxxx"); // 5 bytes bons + lixo
        try (OutputStream out = gateway().openWrite(p("parcial.bin"), 5)) {
            out.write("56789".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(read(p("parcial.bin"))).isEqualTo("0123456789");
    }

    @Test
    void gravarSemDeslocamentoTrunca() throws Exception {
        write(p("t.txt"), "conteúdo longo antigo");
        write(p("t.txt"), "novo");
        assertThat(read(p("t.txt"))).isEqualTo("novo");
    }

    @Test
    void permissoesSaoPreservadas() throws Exception {
        write(p("script.sh"), "#!/bin/sh");
        gateway().setMode(p("script.sh"), 0755);
        assertThat(gateway().lstat(p("script.sh")).mode()).isEqualTo(0755);
        gateway().setMode(p("script.sh"), 0600);
        assertThat(gateway().lstat(p("script.sh")).mode()).isEqualTo(0600);
    }

    @Test
    void dataDeModificacaoEhPreservada() throws Exception {
        write(p("antigo.txt"), "x");
        Instant quando = Instant.parse("2020-05-17T10:20:30Z");
        gateway().setMtime(p("antigo.txt"), quando);
        assertThat(gateway().lstat(p("antigo.txt")).mtime().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(quando);
    }

    @Test
    void linksSimbolicosNaoSaoSeguidos() throws Exception {
        write(p("alvo.txt"), "conteudo do alvo");
        gateway().symlink(p("link-relativo"), "alvo.txt");
        gateway().symlink(p("link-quebrado"), "nao-existe.txt");
        gateway().symlink(p("link-para-pasta"), "/etc");

        FileEntry rel = gateway().lstat(p("link-relativo"));
        assertThat(rel.type()).isEqualTo(FileType.SYMLINK);
        assertThat(rel.linkTarget()).isEqualTo("alvo.txt");
        assertThat(gateway().lstat(p("link-quebrado")).type()).isEqualTo(FileType.SYMLINK);
        assertThat(gateway().lstat(p("link-quebrado")).linkTarget()).isEqualTo("nao-existe.txt");
        assertThat(gateway().lstat(p("link-para-pasta")).type()).isEqualTo(FileType.SYMLINK);
        assertThat(listByName(root()).get("link-para-pasta").linkTarget()).isEqualTo("/etc");
    }

    @Test
    @Timeout(20)
    void fifoEhDetectadoEnuncaAberto() throws Exception {
        Path fifo = hostPath(p("canal"));
        new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start().waitFor();
        FileEntry e = gateway().lstat(p("canal"));
        assertThat(e.type()).isEqualTo(FileType.SPECIAL);
        assertThat(listByName(root()).get("canal").type()).isEqualTo(FileType.SPECIAL);
        assertThatThrownBy(() -> gateway().openRead(p("canal"), 0)).isInstanceOf(IOException.class);
    }

    @Test
    void renomearSubstituiOArquivoExistente() throws Exception {
        write(p("novo.tmp"), "novo");
        write(p("final.txt"), "antigo");
        gateway().rename(p("novo.tmp"), p("final.txt"));
        assertThat(read(p("final.txt"))).isEqualTo("novo");
        assertThat(gateway().lstatIfExists(p("novo.tmp"))).isEmpty();
    }

    @Test
    void criaDiretoriosEExcluiItens() throws Exception {
        gateway().mkdirs(p("a/b/c"));
        gateway().mkdirs(p("a/b/c")); // idempotente
        assertThat(gateway().lstat(p("a/b/c")).type()).isEqualTo(FileType.DIRECTORY);
        write(p("a/b/c/f.txt"), "x");
        gateway().delete(p("a/b/c/f.txt"));
        gateway().delete(p("a/b/c"));
        assertThat(gateway().lstatIfExists(p("a/b/c"))).isEmpty();
    }

    @Test
    void itemInexistenteLancaNoSuchFile() {
        assertThatThrownBy(() -> gateway().lstat(p("nao-existe"))).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> gateway().list(p("nao-existe"))).isInstanceOf(IOException.class);
    }

    @Test
    void espacoLivreEhInformado() throws Exception {
        assertThat(gateway().freeSpace(root())).isPresent();
        assertThat(gateway().freeSpace(root()).getAsLong()).isPositive();
    }

    @Test
    void sha256RemotoQuandoSuportado() throws Exception {
        write(p("hash.txt"), "abc");
        if (gateway().capabilities().remoteHash()) {
            assertThat(gateway().remoteSha256(p("hash.txt")))
                    .contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        } else {
            assertThat(gateway().remoteSha256(p("hash.txt"))).isEmpty();
        }
    }

    @Test
    void arquivoGrandeAtravessaEmStreaming() throws Exception {
        byte[] bloco = new byte[1 << 20];
        java.util.Arrays.fill(bloco, (byte) 'z');
        try (OutputStream out = gateway().openWrite(p("grande.bin"), 0)) {
            for (int i = 0; i < 12; i++) {
                out.write(bloco);
            }
        }
        assertThat(gateway().lstat(p("grande.bin")).size()).isEqualTo(12L << 20);
        long lidos = 0;
        try (InputStream in = gateway().openRead(p("grande.bin"), 0)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                lidos += n;
            }
        }
        assertThat(lidos).isEqualTo(12L << 20);
    }

    protected List<String> nomes(String dir) throws IOException {
        return gateway().list(dir).stream().map(FileEntry::name).sorted().toList();
    }
}
