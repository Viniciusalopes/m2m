package br.com.vovolinux.m2m.files.sftp;

import br.com.vovolinux.m2m.files.GatewayException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.springframework.stereotype.Component;

/** Cria sessões SSH autenticadas, validando a identidade do servidor por impressão digital (D2, D12). */
@Component
public class SftpConnector {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration AUTH_TIMEOUT = Duration.ofSeconds(20);

    private record Context(HostKeyPolicy policy, AtomicReference<GatewayException> rejection) {
    }

    private static final AttributeRepository.AttributeKey<Context> CONTEXT = new AttributeRepository.AttributeKey<>();

    private SshClient client;

    @PostConstruct
    void start() {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, remote, serverKey) -> {
            Context ctx = session.getConnectionContext().getAttribute(CONTEXT);
            if (ctx == null) {
                return false;
            }
            try {
                ctx.policy().verify(KeyUtils.getFingerPrint(serverKey));
                return true;
            } catch (GatewayException e) {
                ctx.rejection().set(e);
                return false;
            }
        });
        // Mantém a sessão viva em transferências longas e detecta quedas
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(30));
        CoreModuleProperties.HEARTBEAT_REQUEST.set(client, "keepalive@openssh.com");
        client.start();
    }

    /** Para testes unitários fora do contêiner do Spring. */
    public void startForTest() {
        start();
    }

    public void stopForTest() {
        stop();
    }

    @PreDestroy
    void stop() {
        if (client != null) {
            client.stop();
        }
    }

    /**
     * @param key      chave privada (autenticação por chave), ou {@code null}
     * @param password senha (autenticação por senha), ou {@code null}
     */
    public ClientSession open(String host, int port, String username, KeyPair key, String password,
            HostKeyPolicy policy) throws IOException {
        AtomicReference<GatewayException> rejection = new AtomicReference<>();
        AttributeRepository context = AttributeRepository.ofKeyValuePair(CONTEXT, new Context(policy, rejection));
        ClientSession session = null;
        try {
            session = client.connect(username, host, port, context, null).verify(CONNECT_TIMEOUT).getSession();
            if (key != null) {
                session.addPublicKeyIdentity(key);
            }
            if (password != null) {
                session.addPasswordIdentity(password);
            }
            session.auth().verify(AUTH_TIMEOUT);
            return session;
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.close(true);
            }
            GatewayException rejeitada = rejection.get();
            if (rejeitada != null) {
                throw rejeitada;
            }
            throw traduzir(host, port, e);
        }
    }

    /** Converte falhas do MINA em erros com código estável. */
    public static GatewayException traduzir(String host, int port, Throwable e) {
        Map<String, Object> params = Map.of("host", host, "porta", port);
        Throwable raiz = e;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        String msg = String.valueOf(e.getMessage());
        if (raiz instanceof UnknownHostException) {
            return new GatewayException("conexao.host-desconhecido", "Host desconhecido: " + host, params, e);
        }
        if (raiz instanceof ConnectException || raiz instanceof NoRouteToHostException
                || raiz instanceof SocketTimeoutException || raiz instanceof java.util.concurrent.TimeoutException
                || msg.toLowerCase().contains("timeout") || msg.contains("timed out")) {
            // endereço de rede privada: o host do m2m provavelmente não está na mesma rede (D33)
            String codigo = br.com.vovolinux.m2m.machine.PrivateAddress.is(host)
                    ? "conexao.rede-privada-inalcancavel" : "conexao.inalcancavel";
            return new GatewayException(codigo, "Sem resposta de " + host + ":" + port, params, e);
        }
        if (msg.contains("Authentication") || msg.contains("auth") || msg.contains("No more authentication")) {
            return new GatewayException("conexao.autenticacao-falhou", "Autenticação recusada por " + host, params, e);
        }
        return new GatewayException("conexao.falha", "Falha ao conectar em " + host + ": " + msg, params, e);
    }

    /** Carrega a chave privada em formato OpenSSH. */
    public static KeyPair loadKey(String privatePem) throws IOException, GeneralSecurityException {
        var in = new ByteArrayInputStream(privatePem.getBytes(StandardCharsets.UTF_8));
        var it = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("m2m"), in, FilePasswordProvider.EMPTY)
                .iterator();
        if (!it.hasNext()) {
            throw new GeneralSecurityException("Chave privada inválida");
        }
        return it.next();
    }

    static String describe(InetSocketAddress a) {
        return a.getHostString() + ":" + a.getPort();
    }
}
