package br.com.vovolinux.m2m.files.ftp;

import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.sftp.HostKeyPolicy;
import br.com.vovolinux.m2m.files.sftp.SftpConnector;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.SSLEngine;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Component;

/**
 * Cria conexões FTP e FTPS (explícito ou implícito) em modo passivo. No FTPS o certificado do servidor não é validado
 * por cadeia de confiança, e sim fixado pela impressão digital SHA-256 confirmada pelo usuário (D12).
 */
@Component
public class FtpConnector {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration DATA_TIMEOUT = Duration.ofSeconds(60);

    /** Valida o certificado do servidor contra a política de identidade, guardando a recusa para quem chamou. */
    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final HostKeyPolicy policy;
        private final AtomicReference<GatewayException> rejection;

        PinningTrustManager(HostKeyPolicy policy, AtomicReference<GatewayException> rejection) {
            this.policy = policy;
            this.rejection = rejection;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                policy.verify(fingerprint(chain[0]));
            } catch (GatewayException e) {
                rejection.set(e);
                throw new CertificateException(e.getMessage(), e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // o m2m não é servidor
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** {@code SHA256:<base64 sem preenchimento>} do certificado, no mesmo formato das chaves SSH. */
    public static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    /**
     * @param tls         {@code null} para FTP puro, {@code "EXPLICIT"} ou {@code "IMPLICIT"} para FTPS
     * @param policy      política de identidade (só no FTPS)
     */
    public FTPClient open(String host, int port, String username, String password, String tls, HostKeyPolicy policy)
            throws IOException {
        AtomicReference<GatewayException> rejection = new AtomicReference<>();
        FTPClient client;
        if (tls == null) {
            client = new FTPClient();
        } else {
            FTPSClient s = new FTPSClient("TLS", "IMPLICIT".equals(tls));
            s.setTrustManager(new PinningTrustManager(policy, rejection));
            s.setEndpointCheckingEnabled(false);
            client = s;
        }
        client.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        client.setDefaultTimeout((int) DATA_TIMEOUT.toMillis());
        client.setDataTimeout(DATA_TIMEOUT);
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        client.setControlEncoding("UTF-8");
        client.setListHiddenFiles(true);
        try {
            client.connect(host, port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new GatewayException("conexao.falha", "Servidor FTP recusou: " + client.getReplyString(),
                        java.util.Map.of("host", host, "porta", port), null);
            }
            if (client instanceof FTPSClient s) {
                s.execPBSZ(0);
                s.execPROT("P");
            }
            if (!client.login(username == null ? "anonymous" : username, password == null ? "" : password)) {
                throw new GatewayException("conexao.autenticacao-falhou", "Autenticação recusada por " + host,
                        java.util.Map.of("host", host, "porta", port), null);
            }
            if (client instanceof FTPSClient s) {
                // login explícito: o canal de controle já está protegido; protege também os dados
                s.execPBSZ(0);
                s.execPROT("P");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.setBufferSize(1 << 20);
            if (client.features() && client.hasFeature("UTF8")) {
                client.sendCommand("OPTS UTF8 ON");
            }
            return client;
        } catch (IOException | RuntimeException e) {
            try {
                client.disconnect();
            } catch (IOException ignorado) {
                // já desconectado
            }
            GatewayException rejeitada = rejection.get();
            if (rejeitada != null) {
                throw rejeitada;
            }
            if (e instanceof GatewayException g) {
                throw g;
            }
            if (e instanceof SSLException ssl && ssl.getCause() instanceof CertificateException ce
                    && ce.getCause() instanceof GatewayException g) {
                throw g;
            }
            throw SftpConnector.traduzir(host, port, e);
        }
    }
}
