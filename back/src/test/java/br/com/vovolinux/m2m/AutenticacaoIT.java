package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class AutenticacaoIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    JdbcClient jdbc;
    @Value("${server.servlet.session.timeout}")
    Duration timeoutSessao;

    private MvcResult login(String user, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").param("username", user).param("password", senha).with(csrf()))
                .andReturn();
    }

    @Test
    void apiSemSessaoRetorna401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void postSemCsrfEhRecusado() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "x").param("password", "y"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminInicialDoAmbienteConsegueEntrar() throws Exception {
        MvcResult r = login("admin", "Senha-Admin-Teste-1");
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        MockHttpSession sessao = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(sessao)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.email").value("admin@exemplo.test"));
    }

    @Test
    void senhaErradaRetorna401ComCodigo() throws Exception {
        var u = usuarios.novo();
        mvc.perform(post("/api/auth/login").param("username", u.username()).param("password", "errada").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("auth.credenciais-invalidas"));
    }

    @Test
    void cincoFalhasBloqueiamMesmoComSenhaCorreta() throws Exception {
        var u = usuarios.novo();
        for (int i = 0; i < 5; i++) {
            assertThat(login(u.username(), "errada").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult bloqueado = login(u.username(), Usuarios.SENHA);
        assertThat(bloqueado.getResponse().getStatus()).isEqualTo(423);
        assertThat(bloqueado.getResponse().getContentAsString()).contains("auth.conta-bloqueada");

        // passados os 5 minutos, o acesso volta
        jdbc.sql("update app_user set locked_until = now() - interval '1 second' where id = :id")
                .param("id", u.id()).update();
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("select failed_attempts from app_user where id = :id").param("id", u.id())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void falhasNaoSeguidasNaoBloqueiam() throws Exception {
        var u = usuarios.novo();
        for (int i = 0; i < 4; i++) {
            login(u.username(), "errada");
        }
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(204);
        for (int i = 0; i < 4; i++) {
            login(u.username(), "errada");
        }
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void sessaoExpiraEm30MinutosSemAtividade() throws Exception {
        assertThat(timeoutSessao).isEqualTo(Duration.ofMinutes(30));
        var u = usuarios.novo();
        MockHttpSession sessao = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        mvc.perform(get("/api/me").session(sessao)).andExpect(status().isOk());
        sessao.invalidate(); // equivale à expiração por inatividade
        mvc.perform(get("/api/me").session(sessao)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEncerraASessao() throws Exception {
        var u = usuarios.novo();
        MockHttpSession sessao = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        mvc.perform(post("/api/auth/logout").session(sessao).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(sessao)).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioDesativadoNaoEntra() throws Exception {
        var u = usuarios.novo();
        jdbc.sql("update app_user set enabled = false where id = :id").param("id", u.id()).update();
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(401);
    }
}
