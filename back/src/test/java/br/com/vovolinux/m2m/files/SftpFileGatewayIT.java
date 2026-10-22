package br.com.vovolinux.m2m.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.files.sftp.SftpConnector;
import br.com.vovolinux.m2m.files.sftp.SftpFileGateway;
import br.com.vovolinux.m2m.support.SftpServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Gateway SFTP contra um servidor OpenSSH real em container (nunca máquinas reais — D19). */
class SftpFileGatewayIT extends FileGatewayContract {

    private static SftpServer server;
    private static SftpConnector connector;

    private ClientSession session;
    private SftpFileGateway gw;
    private String root;

    @BeforeAll
    static void subir() {
        server = SftpServer.start();
        connector = new SftpConnector();
        connector.startForTest();
    }

    @AfterAll
    static void parar() {
        connector.stopForTest();
        server.stop();
    }

    @BeforeEach
    void conectar() throws Exception {
        session = connector.open(server.host(), server.port(), SftpServer.USER, null, SftpServer.PASSWORD, fp -> {
        });
        // sem exec nesta conta (somente SFTP): hash remoto desligado
        gw = new SftpFileGateway(session, Capabilities.full());
        root = "/dados/t-" + UUID.randomUUID();
        gw.mkdirs(root);
    }

    @AfterEach
    void desconectar() {
        gw.close();
    }

    @Override
    protected FileGateway gateway() {
        return gw;
    }

    @Override
    protected String root() {
        return root;
    }

    @Override
    protected Path hostPath(String gatewayPath) {
        return server.hostDir().resolve(gatewayPath.substring("/dados/".length()));
    }

    @Test
    void autenticaPorChavePublica() throws Exception {
        try (ClientSession porChave = connector.open(server.host(), server.port(), SftpServer.USER,
                SftpConnector.loadKey(server.privateKeyPem()), null, fp -> {
                })) {
            assertThat(porChave.isAuthenticated()).isTrue();
        }
    }

    @Test
    void senhaErradaEhRecusadaComCodigo() {
        assertThatThrownBy(() -> connector.open(server.host(), server.port(), SftpServer.USER, null, "errada", fp -> {
        })).isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).codigo()).isEqualTo("conexao.autenticacao-falhou");
    }

    @Test
    void identidadeDoServidorEhPassadaParaAPolitica() {
        String[] vista = new String[1];
        assertThatThrownBy(() -> connector.open(server.host(), server.port(), SftpServer.USER, null,
                SftpServer.PASSWORD, fp -> {
                    vista[0] = fp;
                    throw new GatewayException("identidade.confirmacao-necessaria", "confirme");
                })).isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).codigo()).isEqualTo("identidade.confirmacao-necessaria");
        assertThat(vista[0]).startsWith("SHA256:");
    }

    @Test
    void hostInexistenteEPortaFechadaTemCodigosDistintos() {
        assertThatThrownBy(() -> connector.open("127.0.0.1", 1, "x", null, "y", fp -> {
        })).isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).codigo()).isEqualTo("conexao.inalcancavel");
    }

    @Test
    void cancelamentoPorFechamentoForcadoInterrompeALeitura() throws Exception {
        byte[] bloco = new byte[1 << 20];
        try (var out = gw.openWrite(p("enorme.bin"), 0)) {
            for (int i = 0; i < 20; i++) {
                out.write(bloco);
            }
        }
        var in = gw.openRead(p("enorme.bin"), 0);
        in.read(new byte[1024]);
        gw.close(); // fecha à força
        assertThatThrownBy(() -> {
            byte[] buf = new byte[1 << 16];
            while (in.read(buf) > 0) {
                // lê até falhar
            }
        }).isInstanceOf(IOException.class);
    }
}
