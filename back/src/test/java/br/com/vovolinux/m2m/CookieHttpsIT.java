package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.vovolinux.m2m.support.TestContainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Servidor real: o cookie de sessão só trafega por HTTPS e fora do alcance de scripts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
class CookieHttpsIT {

    @LocalServerPort
    int porta;

    @Test
    void cookieDeSessaoEhSeguroHttpOnlyESameSiteStrict() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + porta;

        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = csrf.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";")[0].substring("XSRF-TOKEN=".length());

        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", xsrf).header("Cookie", "XSRF-TOKEN=" + xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=Senha-Admin-Teste-1")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(204);
        List<String> cookies = login.headers().allValues("set-cookie");
        String sessao = cookies.stream().filter(c -> c.startsWith("M2MSESSION=")).findFirst().orElseThrow();
        assertThat(sessao).contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
    }
}
