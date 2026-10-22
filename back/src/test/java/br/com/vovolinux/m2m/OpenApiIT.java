package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OpenApiIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;

    @Test
    void documentacaoExigeLoginEEhExportadaParaOCliente() throws Exception {
        mvc.perform(get("/api/docs/openapi")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());

        var u = usuarios.novo();
        MockHttpSession sessao = (MockHttpSession) mvc.perform(post("/api/auth/login")
                .param("username", u.username()).param("password", Usuarios.SENHA).with(csrf()))
                .andReturn().getRequest().getSession(false);
        String json = mvc.perform(get("/api/docs/openapi").session(sessao)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("\"/api/me\"").contains("\"/api/audit\"").contains("\"/api/system/master-key\"");
        // consumido por scripts/gerar-cliente-api.sh para gerar o cliente TypeScript do front
        Path destino = Path.of("target", "openapi.json");
        Files.writeString(destino, json);
    }
}
