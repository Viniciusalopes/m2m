package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.security.SecondFactorFilter;
import br.com.vovolinux.m2m.security.Totp;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Segundo fator TOTP: ativação com confirmação, login em duas etapas, recuperação, bloqueio e desativação. */
@IntegrationTest
class SegundoFatorIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;

    private MvcResult login(String user, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").param("username", user).param("password", senha).with(csrf()))
                .andReturn();
    }

    private MockHttpSession entrar(Usuarios.Criado u) throws Exception {
        MvcResult r = login(u.username(), Usuarios.SENHA);
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String codigo(String secret, long deslocamentoDePassos) {
        long passo = Totp.stepOf(System.currentTimeMillis()) + deslocamentoDePassos;
        return Totp.format(Totp.code(Totp.decodeBase32(secret), passo));
    }

    private String codigoJson(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    /** Ativa o segundo fator e devolve {segredo, códigos de recuperação}. */
    private record Ativado(String secret, List<String> recuperacao) {
    }

    private Ativado ativar(Usuarios.Criado u) throws Exception {
        MockHttpSession s = entrar(u);
        MvcResult setup = mvc.perform(post("/api/me/2fa/setup").session(s).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/m2m:")))
                .andReturn();
        String secret = json(setup).get("secret").asText();
        // código errado não ativa
        mvc.perform(post("/api/me/2fa/enable").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson("000000"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("segundo-fator.codigo-invalido"));
        MvcResult ok = mvc.perform(post("/api/me/2fa/enable").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(codigoJson(codigo(secret, 0))))
                .andExpect(status().isOk()).andReturn();
        List<String> codes = json(ok).get("recoveryCodes").valueStream().map(JsonNode::asText).toList();
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));
        mvc.perform(get("/api/me/2fa").session(s)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));
        return new Ativado(secret, codes);
    }

    @Test
    void rfc6238VetorDeTesteEBase32() {
        // vetor do RFC 6238 (SHA-1, segredo "12345678901234567890", T = 59 s → 94287082; 6 dígitos: 287082)
        byte[] key = "12345678901234567890".getBytes();
        assertThat(Totp.code(key, 59 / 30)).isEqualTo(287082);
        assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo(81804);
        assertThat(Totp.encodeBase32(key)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isEqualTo(key);
        // tolerância de ±1 passo e fora disso recusa; passo já usado não vale de novo
        long agora = 1_700_000_000_000L;
        long passo = Totp.stepOf(agora);
        assertThat(Totp.verify(key, Totp.format(Totp.code(key, passo - 1)), agora, -1)).isPresent();
        assertThat(Totp.verify(key, Totp.format(Totp.code(key, passo + 1)), agora, -1)).isPresent();
        assertThat(Totp.verify(key, Totp.format(Totp.code(key, passo + 2)), agora, -1)).isEmpty();
        assertThat(Totp.verify(key, Totp.format(Totp.code(key, passo)), agora, passo)).isEmpty();
    }

    @Test
    void ativarMostraOsCodigosDeRecuperacaoUmaVezEGuardaSoHashes() throws Exception {
        var u = usuarios.novo();
        Ativado a = ativar(u);
        String guardado = jdbc.sql("select recovery_codes::text from user_totp where user_id = :u").param("u", u.id())
                .query(String.class).single();
        a.recuperacao().forEach(c -> assertThat(guardado).doesNotContain(c.replace("-", "")));
        assertThat(guardado).contains("$2a$"); // BCrypt
        String segredoGuardado = new String(jdbc.sql("select secret_enc from user_totp where user_id = :u")
                .param("u", u.id()).query(byte[].class).single(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(segredoGuardado).doesNotContain(a.secret()); // cifrado pela chave mestra
        // ativar de novo com o segundo fator já ligado é recusado
        mvc.perform(post("/api/me/2fa/setup").session(entrar2fa(u, a)).with(csrf())).andExpect(status().isConflict());
    }

    /** Faz o login completo (senha + TOTP do passo seguinte) e devolve a sessão liberada. */
    private MockHttpSession entrar2fa(Usuarios.Criado u, Ativado a) throws Exception {
        MvcResult r = login(u.username(), Usuarios.SENHA);
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        // o código do passo atual foi usado na ativação: usa o seguinte (dentro da tolerância)
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(codigo(a.secret(), 1)))).andExpect(status().isNoContent());
        return s;
    }

    @Test
    void loginExigeSegundoFatorESessaoPendenteNaoAcessaNada() throws Exception {
        var u = usuarios.novo();
        Ativado a = ativar(u);

        MvcResult r = login(u.username(), Usuarios.SENHA);
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        assertThat(r.getResponse().getContentAsString()).contains("segundoFator");
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);

        // com a sessão pendente nada é atendido, nem a própria conta
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("auth.segundo-fator-pendente"));
        mvc.perform(get("/api/machines").session(s)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/2fa/disable").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());

        // código errado: 401 com código estável
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson("123456"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("auth.codigo-invalido"));
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());

        // código certo libera
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(codigo(a.secret(), 1)))).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(u.username()));

        // o mesmo código não vale duas vezes
        MockHttpSession outra = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        mvc.perform(post("/api/auth/2fa").session(outra).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(codigo(a.secret(), 1)))).andExpect(status().isUnauthorized());
    }

    @Test
    void codigoDeRecuperacaoValeUmaVezSo() throws Exception {
        var u = usuarios.novo();
        Ativado a = ativar(u);
        String rec = a.recuperacao().get(0);

        MockHttpSession s = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(rec.toLowerCase()))).andExpect(status().isNoContent()); // sem diferenciar caixa
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/me/2fa").session(s)).andExpect(jsonPath("$.recoveryCodesLeft").value(9));

        MockHttpSession de_novo = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        mvc.perform(post("/api/auth/2fa").session(de_novo).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(rec))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/2fa").session(de_novo).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(a.recuperacao().get(1)))).andExpect(status().isNoContent());
    }

    @Test
    void codigosErradosContamParaOBloqueio() throws Exception {
        var u = usuarios.novo();
        ativar(u);
        MockHttpSession s = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(codigoJson("00000" + i))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson("000009"))).andExpect(status().isLocked())
                .andExpect(jsonPath("$.codigo").value("auth.conta-bloqueada"));
        // bloqueado: nem a senha certa entra mais
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(423);
        // recomeçar o login a cada tentativa não zera as falhas
        jdbc.sql("update app_user set locked_until = now() - interval '1 second' where id = :id").param("id", u.id())
                .update();
        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(202);
    }

    @Test
    void pendenciaExpiradaNaoPodeMaisSerCompletada() throws Exception {
        var u = usuarios.novo();
        Ativado a = ativar(u);
        MockHttpSession s = (MockHttpSession) login(u.username(), Usuarios.SENHA).getRequest().getSession(false);
        s.setAttribute(SecondFactorFilter.PENDING,
                new SecondFactorFilter.Pending(u.id(), Instant.now().minusSeconds(1)));
        mvc.perform(post("/api/auth/2fa").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(codigoJson(codigo(a.secret(), 1)))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("auth.segundo-fator-expirado"));
    }

    @Test
    void desativarExigeSenhaECodigoEDepoisOLoginVoltaAoNormal() throws Exception {
        var u = usuarios.novo();
        Ativado a = ativar(u);
        MockHttpSession s = entrar2fa(u, a);

        mvc.perform(post("/api/me/2fa/disable").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"errada\",\"code\":\"" + a.recuperacao().get(0) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("conta.senha-atual-incorreta"));
        mvc.perform(post("/api/me/2fa/disable").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + Usuarios.SENHA + "\",\"code\":\"999999\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("segundo-fator.codigo-invalido"));
        mvc.perform(post("/api/me/2fa/disable").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + Usuarios.SENHA + "\",\"code\":\"" + a.recuperacao().get(0) + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(login(u.username(), Usuarios.SENHA).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("select count(*) from user_totp where user_id = :u").param("u", u.id()).query(Long.class)
                .single()).isZero();
        // a auditoria registra ativação, desativação e as etapas do login, sem segredos
        List<String> acoes = jdbc.sql("select action from audit_event where username = :u order by id")
                .param("u", u.username()).query(String.class).list();
        assertThat(acoes).contains("TOTP_ENABLE", "TOTP_DISABLE", "LOGIN_2FA", "LOGIN");
        String detalhes = String.join(" ", jdbc.sql("select details::text from audit_event where username = :u")
                .param("u", u.username()).query(String.class).list());
        assertThat(detalhes).doesNotContain(a.secret());
    }
}
