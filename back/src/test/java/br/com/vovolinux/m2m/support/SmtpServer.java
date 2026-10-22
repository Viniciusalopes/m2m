package br.com.vovolinux.m2m.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Servidor SMTP de teste (MailDev) em container: recebe e-mails e os expõe por HTTP. */
public final class SmtpServer {

    private final GenericContainer<?> container;

    private SmtpServer(GenericContainer<?> container) {
        this.container = container;
    }

    public static SmtpServer start() {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("maildev/maildev:latest"))
                .withExposedPorts(1025, 1080).waitingFor(Wait.forHttp("/api/email").forPort(1080).forStatusCode(200));
        c.start();
        return new SmtpServer(c);
    }

    public String host() {
        return container.getHost();
    }

    public int smtpPort() {
        return container.getMappedPort(1025);
    }

    /** Todos os e-mails recebidos, em JSON. */
    public String emails() throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(URI.create("http://" + host() + ":"
                + container.getMappedPort(1080) + "/api/email")).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    public void clear() throws Exception {
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(URI.create("http://" + host() + ":"
                + container.getMappedPort(1080) + "/api/email/all")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    public void stop() {
        container.stop();
    }
}
