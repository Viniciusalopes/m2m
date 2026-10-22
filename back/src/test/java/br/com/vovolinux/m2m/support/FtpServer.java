package br.com.vovolinux.m2m.support;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Servidor FTP (pure-ftpd) em container, com as portas passivas publicadas nos mesmos números do host: o servidor
 * anuncia a porta passiva do container e o cliente precisa alcançá-la igual. FTPS é explícito ({@code AUTH TLS}) com
 * certificado autoassinado; o pure-ftpd não oferece FTPS implícito.
 */
public final class FtpServer {

    public static final String USER = "m2m";
    public static final String PASSWORD = "m2m-teste";

    private final GenericContainer<?> container;
    private final int port;

    private FtpServer(GenericContainer<?> container, int port) {
        this.container = container;
        this.port = port;
    }

    /** @param tls {@code true} para FTPS explícito; usa faixas de porta diferentes para poder rodar os dois juntos */
    public static FtpServer start(boolean tls) {
        int control = tls ? 2142 : 2141;
        int passiveFrom = tls ? 30120 : 30100;
        int passiveTo = passiveFrom + 9;
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("stilliard/pure-ftpd:latest"))
                .withEnv("PUBLICHOST", "127.0.0.1").withEnv("FTP_USER_NAME", USER).withEnv("FTP_USER_PASS", PASSWORD)
                .withEnv("FTP_USER_HOME", "/home/m2m").withEnv("FTP_PASSIVE_PORTS", passiveFrom + ":" + passiveTo)
                .withExposedPorts(21)
                .waitingFor(Wait.forListeningPorts(21).withStartupTimeout(java.time.Duration.ofSeconds(300)));
        // o padrão do pure-ftpd (5 conexões por IP) é pouco para o pool de conexões do m2m
        c.withEnv("FTP_MAX_CLIENTS", "100").withEnv("FTP_MAX_CONNECTIONS", "100");
        if (tls) {
            c.withEnv("TLS_CN", "127.0.0.1").withEnv("TLS_ORG", "m2m").withEnv("TLS_C", "BR")
                    .withEnv("ADDED_FLAGS", "--tls=1");
        }
        List<String> bindings = new ArrayList<>();
        bindings.add(control + ":21");
        for (int p = passiveFrom; p <= passiveTo; p++) {
            bindings.add(p + ":" + p);
        }
        c.setPortBindings(bindings);
        c.start();
        return new FtpServer(c, control);
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return port;
    }

    public void stop() {
        container.stop();
    }
}
