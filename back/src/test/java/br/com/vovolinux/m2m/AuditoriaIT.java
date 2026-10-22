package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.audit.AuditRepository;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuditoriaIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AuditRepository auditRepository;

    private MockHttpSession entrar(Usuarios.Criado u, String senha) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", u.username())
                .param("password", senha).with(csrf())).andReturn().getRequest().getSession(false);
    }

    @Test
    void loginComSucessoEFalhaSaoAuditados() throws Exception {
        var u = usuarios.novo();
        mvc.perform(post("/api/auth/login").param("username", u.username()).param("password", "errada").with(csrf()));
        MockHttpSession sessao = entrar(u, Usuarios.SENHA);

        mvc.perform(get("/api/audit").param("action", "LOGIN").session(sessao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.items[1].result").value("FAILURE"))
                .andExpect(jsonPath("$.items[0].username").value(u.username()));
    }

    @Test
    void alterarSenhaEhAuditadoSemGravarSegredos() throws Exception {
        var u = usuarios.novo();
        MockHttpSession sessao = entrar(u, Usuarios.SENHA);
        String novaSenha = "Nova-Senha-Segura-9";
        mvc.perform(put("/api/me/password").session(sessao).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + Usuarios.SENHA + "\",\"newPassword\":\"" + novaSenha + "\"}"))
                .andExpect(status().isNoContent());

        String todos = mvc.perform(get("/api/audit").param("action", "PASSWORD_CHANGE").session(sessao))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn().getResponse()
                .getContentAsString();
        assertThat(todos).doesNotContain(novaSenha).doesNotContain(Usuarios.SENHA);

        // a nova senha vale, a antiga não
        assertThat(entrar(u, novaSenha)).isNotNull();
        mvc.perform(post("/api/auth/login").param("username", u.username()).param("password", Usuarios.SENHA)
                .with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void senhaAtualIncorretaNaoTrocaEAudita() throws Exception {
        var u = usuarios.novo();
        MockHttpSession sessao = entrar(u, Usuarios.SENHA);
        mvc.perform(put("/api/me/password").session(sessao).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"nao-e-essa\",\"newPassword\":\"Nova-Senha-Segura-9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("conta.senha-atual-incorreta"));
        mvc.perform(get("/api/audit").param("action", "PASSWORD_CHANGE").session(sessao))
                .andExpect(jsonPath("$.items[0].result").value("FAILURE"));
    }

    @Test
    void cadaUsuarioVeApenasOsProprioEventos() throws Exception {
        var a = usuarios.novo();
        var b = usuarios.novo();
        MockHttpSession sa = entrar(a, Usuarios.SENHA);
        MockHttpSession sb = entrar(b, Usuarios.SENHA);
        mvc.perform(get("/api/audit").session(sa)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/audit").session(sb)).andExpect(jsonPath("$.items[0].username").value(b.username()));
        mvc.perform(get("/api/audit").param("user", a.username()).session(sb))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void exportaCsvComCabecalho() throws Exception {
        var u = usuarios.novo();
        MockHttpSession sessao = entrar(u, Usuarios.SENHA);
        mvc.perform(get("/api/audit/export").session(sessao))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data_hora;usuario;ip;acao;alvo;resultado;detalhes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"LOGIN\"")));
    }

    @Test
    void bancoRecusaAlterarOuApagarEventoRecente() {
        var u = usuarios.novo();
        auditRepository.insert(u.id(), u.username(), "127.0.0.1", "TESTE", "alvo", "SUCCESS", "{}");
        assertThatThrownBy(() -> jdbc.sql("update audit_event set result = 'X' where owner_id = :o")
                .param("o", u.id()).update()).hasMessageContaining("não pode ser alterado");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_event where owner_id = :o")
                .param("o", u.id()).update()).hasMessageContaining("365 dias");
    }

    @Test
    void limpezaRemoveSoMaisDe365Dias() {
        var u = usuarios.novo();
        jdbc.sql("""
                insert into audit_event (owner_id, occurred_at, username, action, result)
                values (:o, now() - interval '400 days', 'x', 'ANTIGO', 'SUCCESS'),
                       (:o, now() - interval '364 days', 'x', 'RECENTE', 'SUCCESS')""").param("o", u.id()).update();
        int removidos = auditRepository.deleteOlderThanDays(365);
        assertThat(removidos).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_event where owner_id = :o and action = 'ANTIGO'")
                .param("o", u.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from audit_event where owner_id = :o and action = 'RECENTE'")
                .param("o", u.id()).query(Long.class).single()).isEqualTo(1);
    }
}
