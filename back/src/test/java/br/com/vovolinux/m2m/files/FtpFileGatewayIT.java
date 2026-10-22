package br.com.vovolinux.m2m.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.files.ftp.FtpConnector;
import br.com.vovolinux.m2m.files.ftp.FtpFileGateway;
import br.com.vovolinux.m2m.machine.IdentityException;
import br.com.vovolinux.m2m.support.FtpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O gateway FTP/FTPS contra servidores pure-ftpd em container (nunca máquinas reais, D19). */
class FtpFileGatewayIT {

    private static FtpServer ftp;
    private static FtpServer ftps;
    private final FtpConnector connector = new FtpConnector();

    private FtpFileGateway gw;
    private String root;

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

    private FtpFileGateway abrir(FtpServer s, String tls, br.com.vovolinux.m2m.files.sftp.HostKeyPolicy policy)
            throws IOException {
        var client = connector.open(s.host(), s.port(), FtpServer.USER, FtpServer.PASSWORD, tls, policy);
        return new FtpFileGateway(client, new Capabilities(false, false, true, false, false, true));
    }

    @BeforeEach
    void preparar() throws IOException {
        gw = abrir(ftp, null, null);
        root = "/t-" + UUID.randomUUID().toString().substring(0, 8);
        gw.mkdirs(root);
    }

    @AfterEach
    void limpar() throws IOException {
        try {
            FsOps.deleteTree(gw, root);
        } finally {
            gw.close();
        }
    }

    private void write(String path, String content) throws IOException {
        try (OutputStream out = gw.openWrite(path, 0)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String read(String path) throws IOException {
        try (InputStream in = gw.openRead(path, 0)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listaOcultosVaziosEAcentos() throws IOException {
        write(root + "/.oculto", "a");
        write(root + "/vazio.bin", "");
        write(root + "/ação com espaço.txt", "ç");
        gw.mkdirs(root + "/.pasta-oculta/sub");

        Map<String, FileEntry> itens = gw.list(root).stream()
                .collect(Collectors.toMap(FileEntry::name, Function.identity()));
        assertThat(itens).containsKeys(".oculto", "vazio.bin", "ação com espaço.txt", ".pasta-oculta");
        assertThat(itens.get("vazio.bin").size()).isZero();
        assertThat(itens.get(".pasta-oculta").type()).isEqualTo(FileType.DIRECTORY);
        assertThat(itens.get("ação com espaço.txt").size()).isEqualTo(2); // "ç" em UTF-8
        assertThat(read(root + "/ação com espaço.txt")).isEqualTo("ç");
        assertThat(gw.list(root)).extracting(FileEntry::name).doesNotContain(".", "..");
    }

    @Test
    void leituraComDeslocamentoEGravacaoRetomada() throws IOException {
        write(root + "/a.txt", "0123456789");
        try (InputStream in = gw.openRead(root + "/a.txt", 4)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("456789");
        }
        // continua a gravação a partir do que já existe (APPE)
        write(root + "/b.txt", "01234");
        try (OutputStream out = gw.openWrite(root + "/b.txt", 5)) {
            out.write("56789".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(read(root + "/b.txt")).isEqualTo("0123456789");
        // gravar sem deslocamento trunca
        write(root + "/b.txt", "xy");
        assertThat(read(root + "/b.txt")).isEqualTo("xy");
    }

    @Test
    void fecharAntesDoFimNaoQuebraAProximaOperacao() throws IOException {
        byte[] grande = new byte[3_000_000];
        new Random(1).nextBytes(grande);
        try (OutputStream out = gw.openWrite(root + "/g.bin", 0)) {
            out.write(grande);
        }
        try (InputStream in = gw.openRead(root + "/g.bin", 0)) {
            assertThat(in.read(new byte[1000])).isEqualTo(1000);
        } // fecha no meio: aborta a transferência
        assertThat(gw.lstat(root + "/g.bin").size()).isEqualTo(3_000_000);
        assertThat(gw.list(root)).hasSize(1);
    }

    @Test
    void dataPorMfmtQuandoOServidorAnuncia() throws IOException {
        write(root + "/m.txt", "m");
        Instant alvo = Instant.parse("2020-05-17T10:20:30Z");
        gw.setMtime(root + "/m.txt", alvo);
        if (gw.mfmtSupported()) {
            assertThat(gw.lstat(root + "/m.txt").mtime().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(alvo);
        }
    }

    @Test
    void renomearSubstituiEExcluirRemoveArquivosEDiretorios() throws IOException {
        write(root + "/orig.txt", "novo");
        write(root + "/dest.txt", "velho");
        gw.rename(root + "/orig.txt", root + "/dest.txt");
        assertThat(read(root + "/dest.txt")).isEqualTo("novo");
        assertThatThrownBy(() -> gw.lstat(root + "/orig.txt")).isInstanceOf(NoSuchFileException.class);

        gw.mkdirs(root + "/d1/d2");
        write(root + "/d1/d2/f.txt", "f");
        FsOps.deleteTree(gw, root + "/d1");
        assertThat(gw.lstatIfExists(root + "/d1")).isEmpty();
    }

    @Test
    void itemInexistenteLancaNoSuchFileEMkdirsEhIdempotente() throws IOException {
        assertThatThrownBy(() -> gw.lstat(root + "/nao-existe")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> gw.list(root + "/nao-existe")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> gw.openRead(root + "/nao-existe", 0)).isInstanceOf(NoSuchFileException.class);
        gw.mkdirs(root + "/x/y/z");
        gw.mkdirs(root + "/x/y/z");
        assertThat(gw.lstat(root + "/x/y/z").isDirectory()).isTrue();
    }

    @Test
    void arquivoGrandeAtravessaEmStreaming() throws IOException {
        byte[] dados = new byte[25 * 1024 * 1024];
        new Random(42).nextBytes(dados);
        try (OutputStream out = gw.openWrite(root + "/grande.bin", 0)) {
            out.write(dados);
        }
        assertThat(gw.lstat(root + "/grande.bin").size()).isEqualTo(dados.length);
        try (InputStream in = gw.openRead(root + "/grande.bin", 0)) {
            assertThat(in.readAllBytes()).isEqualTo(dados);
        }
    }

    @Test
    void canonicoEDeteccaoDeCiclosEstaoDisponiveis() throws IOException {
        gw.mkdirs(root + "/a/b");
        assertThat(gw.detectsCycles()).isTrue();
        assertThat(gw.canonicalDir(root + "/a/b")).isEqualTo(root + "/a/b");
        assertThat(gw.homeDir()).isEqualTo("/");
    }

    @Test
    void ftpsFixaOCertificadoPelaImpressaoDigital() throws IOException {
        AtomicReference<String> vista = new AtomicReference<>();
        // primeira conexão: a política recusa e informa a impressão digital do certificado
        assertThatThrownBy(() -> abrir(ftps, "EXPLICIT", f -> {
            vista.set(f);
            throw new IdentityException(IdentityException.CONFIRMACAO_NECESSARIA, f, null);
        })).isInstanceOf(IdentityException.class);
        assertThat(vista.get()).startsWith("SHA256:");

        // com a impressão confirmada, conecta e opera sobre TLS
        try (FtpFileGateway tls = abrir(ftps, "EXPLICIT", f -> {
            if (!f.equals(vista.get())) {
                throw new IdentityException(IdentityException.ALTERADA, f, vista.get());
            }
        })) {
            tls.mkdirs("/tls-" + root.substring(3));
            tls.delete("/tls-" + root.substring(3));
            assertThat(tls.list("/")).isNotNull();
        }

        // impressão diferente da confirmada: recusa como "alterada"
        assertThatThrownBy(() -> abrir(ftps, "EXPLICIT", f -> {
            throw new IdentityException(IdentityException.ALTERADA, f, "SHA256:outra");
        })).isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).codigo()).isEqualTo(IdentityException.ALTERADA));
    }

    @Test
    void credencialErradaFalhaComCodigoDeAutenticacao() {
        assertThatThrownBy(() -> connector.open(ftp.host(), ftp.port(), FtpServer.USER, "senha-errada", null, null))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).codigo()).isEqualTo("conexao.autenticacao-falhou"));
    }
}
