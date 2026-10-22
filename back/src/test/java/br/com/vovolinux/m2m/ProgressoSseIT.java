package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.support.TestContainersConfig;
import br.com.vovolinux.m2m.support.TestHandlers;
import br.com.vovolinux.m2m.support.TestHandlers.TestJobHandler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Progresso em tempo real: o navegador recebe o estado atual ao abrir e depois cada atualização até o fim. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestContainersConfig.class, TestHandlers.class})
class ProgressoSseIT {

    @LocalServerPort
    int porta;
    @Autowired
    JobService jobs;
    @Autowired
    TestJobHandler handler;
    @Autowired
    JdbcClient jdbc;

    private String entrar(HttpClient http) throws Exception {
        String base = "http://localhost:" + porta;
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = csrf.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";")[0].substring("XSRF-TOKEN=".length());
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", xsrf)
                .header("Cookie", "XSRF-TOKEN=" + xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=Senha-Admin-Teste-1")).build(),
                HttpResponse.BodyHandlers.ofString());
        return login.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("M2MSESSION="))
                .findFirst().orElseThrow().split(";")[0];
    }

    @Test
    void recebeProgressoEOFimDoJobPorSse() throws Exception {
        handler.reset();
        HttpClient http = HttpClient.newHttpClient();
        String sessao = entrar(http);
        long admin = jdbc.sql("select id from app_user where username = 'admin'").query(Long.class).single();
        Job job = jobs.enqueue(admin, "TEST", "MANUAL", null, null, Map.of("name", "sse", "mode", "wait"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> handler.started.contains("sse"));

        HttpResponse<java.io.InputStream> resp = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + "/api/jobs/" + job.id() + "/events"))
                .header("Cookie", sessao).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");

        List<String> eventos = new ArrayList<>();
        Thread leitor = Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String linha;
                while ((linha = r.readLine()) != null) {
                    if (linha.startsWith("data:")) {
                        synchronized (eventos) {
                            eventos.add(linha.substring(5).trim());
                        }
                    }
                }
            } catch (java.io.IOException ignorado) {
                // fim do fluxo
            }
        });

        // 1) o estado atual chega ao abrir (reabrir a tela durante a execução)
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            synchronized (eventos) {
                return eventos.stream().anyMatch(e -> e.contains("\"status\":\"RUNNING\""));
            }
        });
        // 2) atualizações do RabbitMQ (m2m.progress) chegam com porcentagem e arquivo atual
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            synchronized (eventos) {
                return eventos.stream().anyMatch(e -> e.contains("arquivo-sse.txt") && e.contains("\"percent\":25.0"));
            }
        });

        handler.release("sse");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            synchronized (eventos) {
                return eventos.stream().anyMatch(e -> e.contains("\"type\":\"done\"") && e.contains("COMPLETED"));
            }
        });
        leitor.interrupt();
    }

    @Test
    void semSessaoOStreamRetorna401() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + "/api/jobs/1/events")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
    }
}
