package br.com.vovolinux.m2m;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ChaveMestraIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    JdbcClient jdbc;

    @Test
    void avisoPersisteAteConfirmarEDepoisSome() throws Exception {
        jdbc.sql("delete from app_setting where key = 'master_key_acknowledged'").update();
        var u = usuarios.novo();
        MockHttpSession sessao = (MockHttpSession) mvc.perform(post("/api/auth/login")
                .param("username", u.username()).param("password", Usuarios.SENHA).with(csrf()))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/system/master-key").session(sessao)).andExpect(jsonPath("$.acknowledged").value(false));
        // permanece enquanto não confirmar, mesmo depois de outras chamadas
        mvc.perform(get("/api/me").session(sessao)).andExpect(status().isOk());
        mvc.perform(get("/api/system/master-key").session(sessao)).andExpect(jsonPath("$.acknowledged").value(false));

        mvc.perform(post("/api/system/master-key/acknowledge").session(sessao).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/system/master-key").session(sessao)).andExpect(jsonPath("$.acknowledged").value(true));
        mvc.perform(get("/api/audit").param("action", "MASTER_KEY_ACK").session(sessao))
                .andExpect(jsonPath("$.total").value(1));
    }
}
