package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.machine.MachineUsageSource;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import br.com.vovolinux.m2m.support.SftpServer;
import br.com.vovolinux.m2m.support.Usuarios;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(MaquinasIT.UsoFalso.class)
class MaquinasIT {

    /** Simula um perfil que usa a máquina chamada "Em Uso". */
    @TestConfiguration
    static class UsoFalso {
        @Bean
        MachineUsageSource usoFalso(JdbcClient jdbc) {
            return (owner, machineId) -> jdbc.sql("select name from machine where id = :id and name = 'Em Uso'")
                    .param("id", machineId).query(String.class).list().stream()
                    .map(n -> "Perfil Trabalho (origem)").toList();
        }
    }

    private static OpenSshServer ssh;
    private static SftpServer sftpChroot;

    @Autowired
    MockMvc mvc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;

    @BeforeAll
    static void subir() {
        ssh = OpenSshServer.start();
        sftpChroot = SftpServer.start();
    }

    @AfterAll
    static void parar() {
        ssh.stop();
        sftpChroot.stop();
    }

    private MockHttpSession entrar(Usuarios.Criado u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", u.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
    }

    private MockHttpSession entrarAdmin() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
    }

    private JsonNode criar(MockHttpSession s, String corpo) throws Exception {
        MvcResult r = mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String sftpPassword(String nome, OpenSshServer srv) {
        return """
                {"name":"%s","host":"%s","protocol":"SFTP","port":%d,"username":"%s","authType":"PASSWORD","password":"%s"}"""
                .formatted(nome, srv.host(), srv.port(), OpenSshServer.USER, OpenSshServer.PASSWORD);
    }

    @Test
    void valoresIniciaisDoHpEnvy() throws Exception {
        MockHttpSession s = entrarAdmin();
        mvc.perform(get("/api/machines").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='NOTEBOOK')].protocol").value(hasItem("LOCAL")))
                .andExpect(jsonPath("$[?(@.name=='NOTEBOOK-REMOTO')].host").value(hasItem("192.168.0.10")))
                .andExpect(jsonPath("$[?(@.name=='NOTEBOOK-REMOTO')].port").value(hasItem(22)))
                .andExpect(jsonPath("$[?(@.name=='NOTEBOOK-REMOTO')].protocol").value(hasItem("SFTP")));
    }

    @Test
    void cadastraComPortaPadraoENuncaDevolveASenha() throws Exception {
        var u = usuarios.novo();
        MockHttpSession s = entrar(u);
        MvcResult r = mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"VPS","host":"vps.exemplo.com","protocol":"SFTP","username":"deploy",
                         "authType":"PASSWORD","password":"segredo-super-secreto"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.port").value(22))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.identity.confirmed").value(false))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("segredo-super-secreto");

        // a senha está cifrada no banco
        byte[] enc = jdbc.sql("select password_enc from machine where owner_id = :o and name = 'VPS'")
                .param("o", u.id()).query(byte[].class).single();
        assertThat(new String(enc)).doesNotContain("segredo-super-secreto");

        // e a listagem/consulta tampouco a devolve
        mvc.perform(get("/api/machines").session(s)).andExpect(content().string(not(containsString("segredo"))));
    }

    @Test
    void portasPadraoPorProtocoloEAlertaDeFtpSemTls() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        JsonNode ftp = criar(s, """
                {"name":"Legado","host":"10.0.0.5","protocol":"FTP","username":"u","password":"p"}""");
        assertThat(ftp.get("port").asInt()).isEqualTo(21);
        assertThat(ftp.get("warnings").toString()).contains("ftp.sem-tls").contains("fidelidade.parcial");
        assertThat(ftp.get("fidelity").get("permissions").asBoolean()).isFalse();
        assertThat(ftp.get("fidelity").get("symlinks").asBoolean()).isFalse();

        JsonNode implicito = criar(s, """
                {"name":"Seguro","host":"h","protocol":"FTPS","ftpsMode":"IMPLICIT","username":"u","password":"p"}""");
        assertThat(implicito.get("port").asInt()).isEqualTo(990);
        JsonNode explicito = criar(s, """
                {"name":"Seguro2","host":"h","protocol":"FTPS","username":"u","password":"p"}""");
        assertThat(explicito.get("port").asInt()).isEqualTo(21);
        assertThat(explicito.get("warnings").toString()).doesNotContain("ftp.sem-tls");
    }

    @Test
    void nomeDuplicadoEhRejeitado() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        criar(s, sftpPassword("Repetida", ssh));
        mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sftpPassword("repetida", ssh)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("maquina.nome-duplicado"));
    }

    @Test
    void soExisteUmaMaquinaLocal() throws Exception {
        // o admin já tem o NOTEBOOK (LOCAL) do seed; outro usuário não pode criar uma segunda LOCAL
        MockHttpSession s = entrar(usuarios.novo());
        mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Outro\",\"protocol\":\"LOCAL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("maquina.local-ja-existe"));
    }

    @Test
    void camposObrigatoriosPorProtocolo() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"protocol\":\"SFTP\",\"username\":\"u\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("maquina.host-obrigatorio"));
        mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"protocol\":\"FTP\",\"host\":\"h\",\"username\":\"u\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("maquina.senha-obrigatoria"));
    }

    @Test
    void cadaUsuarioVeApenasSuasMaquinas() throws Exception {
        MockHttpSession a = entrar(usuarios.novo());
        MockHttpSession b = entrar(usuarios.novo());
        long id = criar(a, sftpPassword("Da A", ssh)).get("id").asLong();
        mvc.perform(get("/api/machines/" + id).session(b)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/machines/" + id).session(b).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines").session(b)).andExpect(jsonPath("$[?(@.name=='Da A')]").isEmpty());
    }

    @Test
    void editaMantendoASenhaQuandoOCampoVemVazio() throws Exception {
        var u = usuarios.novo();
        MockHttpSession s = entrar(u);
        long id = criar(s, sftpPassword("Editavel", ssh)).get("id").asLong();
        byte[] antes = jdbc.sql("select password_enc from machine where id = :id").param("id", id)
                .query(byte[].class).single();
        mvc.perform(put("/api/machines/" + id).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Editavel 2","host":"%s","protocol":"SFTP","port":%d,"username":"m2m",
                         "authType":"PASSWORD","password":""}""".formatted(ssh.host(), ssh.port())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Editavel 2"))
                .andExpect(jsonPath("$.hasPassword").value(true));
        byte[] depois = jdbc.sql("select password_enc from machine where id = :id").param("id", id)
                .query(byte[].class).single();
        assertThat(depois).isEqualTo(antes);
    }

    @Test
    void removerMaquinaEmUsoEhRecusadoComAListaDeUsos() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        long emUso = criar(s, sftpPassword("Em Uso", ssh)).get("id").asLong();
        long livre = criar(s, sftpPassword("Livre", ssh)).get("id").asLong();

        mvc.perform(delete("/api/machines/" + emUso).session(s).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("maquina.em-uso"))
                .andExpect(jsonPath("$.parametros.usos[0]").value("Perfil Trabalho (origem)"));
        mvc.perform(get("/api/machines/" + emUso).session(s)).andExpect(status().isOk());

        mvc.perform(delete("/api/machines/" + livre).session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/machines/" + livre).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/audit").param("action", "MACHINE_DELETE").session(s))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void fluxoDeIdentidadeEDeTesteDeConexao() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        long id = criar(s, sftpPassword("Servidor", ssh)).get("id").asLong();

        // 1ª conexão: pede confirmação da impressão digital
        MvcResult primeira = mvc.perform(post("/api/machines/" + id + "/test").session(s).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.steps[?(@.name=='identidade')].status").value(hasItem("ACTION")))
                .andReturn();
        String fingerprint = json.readTree(primeira.getResponse().getContentAsString()).get("fingerprint").asText();
        assertThat(fingerprint).startsWith("SHA256:");
        mvc.perform(get("/api/machines/" + id).session(s)).andExpect(jsonPath("$.identity.pendingFingerprint")
                .value(fingerprint)).andExpect(jsonPath("$.identity.confirmed").value(false));

        // confirmar uma impressão diferente da vista é recusado
        mvc.perform(post("/api/machines/" + id + "/identity/confirm").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"SHA256:outra\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("identidade.fingerprint-diferente"));
        mvc.perform(post("/api/machines/" + id + "/identity/confirm").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + fingerprint + "\"}"))
                .andExpect(status().isNoContent());

        // conexão bem-sucedida: alcance, autenticação, leitura e escrita + recursos
        mvc.perform(post("/api/machines/" + id + "/test").session(s).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.steps[*].name").value(containsInAnyOrder(
                        "alcance", "autenticacao", "identidade", "leitura", "escrita")))
                .andExpect(jsonPath("$.features").value(hasItem("exec")))
                .andExpect(jsonPath("$.features").value(hasItem("statvfs")));
        // o teste de escrita não deixa rastros
        try (var arquivos = Files.list(ssh.homeOnHost())) {
            assertThat(arquivos.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".m2m-write-test")))
                    .isEmpty();
        }
        // recursos detectados ficam salvos e valem para as próximas conexões
        mvc.perform(get("/api/machines/" + id).session(s)).andExpect(jsonPath("$.capabilities.remoteHash").value(true))
                .andExpect(jsonPath("$.capabilities.freeSpace").value(true))
                .andExpect(jsonPath("$.identity.confirmed").value(true))
                .andExpect(jsonPath("$.lastTestAt").exists());
    }

    @Test
    void identidadeAlteradaRecusaAConexaoAteNovaConfirmacao() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        long id = criar(s, sftpPassword("Trocado", ssh)).get("id").asLong();
        jdbc.sql("""
                insert into server_identity (machine_id, kind, fingerprint, confirmed_at)
                values (:m, 'SSH', 'SHA256:impressao-antiga', now())""").param("m", id).update();

        MvcResult r = mvc.perform(post("/api/machines/" + id + "/test").session(s).with(csrf()))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.steps[?(@.name=='identidade')].code").value(hasItem("identidade.alterada")))
                .andReturn();
        String nova = json.readTree(r.getResponse().getContentAsString()).get("fingerprint").asText();
        // navegar também é recusado
        mvc.perform(get("/api/machines/" + id + "/browse").session(s)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("identidade.alterada"));

        mvc.perform(post("/api/machines/" + id + "/identity/confirm").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + nova + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/machines/" + id + "/browse").session(s)).andExpect(status().isOk());
    }

    @Test
    void semPermissaoDeEscritaAMaquinaSoServeDeOrigem() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        // servidor com chroot: a raiz do usuário não é gravável
        long id = criar(s, """
                {"name":"SoLeitura","host":"%s","protocol":"SFTP","port":%d,"username":"%s",
                 "authType":"PASSWORD","password":"%s"}""".formatted(sftpChroot.host(), sftpChroot.port(),
                SftpServer.USER, SftpServer.PASSWORD)).get("id").asLong();
        MvcResult p = mvc.perform(post("/api/machines/" + id + "/test").session(s).with(csrf())).andReturn();
        String fp = json.readTree(p.getResponse().getContentAsString()).get("fingerprint").asText();
        mvc.perform(post("/api/machines/" + id + "/identity/confirm").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + fp + "\"}"));

        mvc.perform(post("/api/machines/" + id + "/test").session(s).with(csrf()))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.steps[?(@.name=='leitura')].status").value(hasItem("OK")))
                .andExpect(jsonPath("$.steps[?(@.name=='escrita')].code").value(hasItem("escrita.sem-permissao")));
        mvc.perform(get("/api/machines/" + id).session(s)).andExpect(jsonPath("$.capabilities.writable").value(false));
    }

    @Test
    void senhaErradaEHostInalcancavelTemMensagensProprias() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        long errada = criar(s, """
                {"name":"SenhaErrada","host":"%s","protocol":"SFTP","port":%d,"username":"m2m",
                 "authType":"PASSWORD","password":"errada"}""".formatted(ssh.host(), ssh.port())).get("id").asLong();
        // confirma a identidade para chegar até a autenticação
        MvcResult p = mvc.perform(post("/api/machines/" + errada + "/test").session(s).with(csrf())).andReturn();
        String fp = json.readTree(p.getResponse().getContentAsString()).get("fingerprint").asText();
        mvc.perform(post("/api/machines/" + errada + "/identity/confirm").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + fp + "\"}"));
        mvc.perform(post("/api/machines/" + errada + "/test").session(s).with(csrf()))
                .andExpect(jsonPath("$.steps[?(@.name=='autenticacao')].code").value(hasItem("conexao.autenticacao-falhou")));

        long fechada = criar(s, """
                {"name":"Fechada","host":"127.0.0.1","protocol":"SFTP","port":1,"username":"m2m",
                 "authType":"PASSWORD","password":"x"}""").get("id").asLong();
        mvc.perform(post("/api/machines/" + fechada + "/test").session(s).with(csrf()))
                .andExpect(jsonPath("$.steps[?(@.name=='alcance')].code").value(hasItem("conexao.inalcancavel")));

        // endereço privado inalcançável: a mensagem explica a rede (D33) — TEST-NET não roteia, então dá timeout
        long privada = criar(s, """
                {"name":"Privada","host":"10.255.255.1","protocol":"SFTP","username":"m2m",
                 "authType":"PASSWORD","password":"x"}""").get("id").asLong();
        mvc.perform(post("/api/machines/" + privada + "/test").session(s).with(csrf()))
                .andExpect(jsonPath("$.steps[?(@.name=='alcance')].code").value(hasItem("conexao.rede-privada-inalcancavel")));
    }

    @Test
    void chavePublicaDoUsuarioComComandoSugerido() throws Exception {
        MockHttpSession s = entrar(usuarios.novo());
        MvcResult r = mvc.perform(get("/api/ssh-identity").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(org.hamcrest.Matchers.startsWith("ssh-ed25519 ")))
                .andExpect(jsonPath("$.suggestedCommand").value(containsString("authorized_keys"))).andReturn();
        String pub = json.readTree(r.getResponse().getContentAsString()).get("publicKey").asText();
        // estável: a mesma chave a cada consulta; a privada nunca aparece
        mvc.perform(get("/api/ssh-identity").session(s)).andExpect(jsonPath("$.publicKey").value(pub));
        assertThat(r.getResponse().getContentAsString()).doesNotContain("PRIVATE KEY");
    }

    @Test
    void navegacaoLocalListaDiretoriosDoHost() throws Exception {
        Files.createDirectories(java.nio.file.Path.of("target/test-hostfs/home/teste/pasta-a"));
        Files.writeString(java.nio.file.Path.of("target/test-hostfs/home/teste/.oculto"), "x");
        MockHttpSession s = entrarAdmin();
        long local = json.readTree(mvc.perform(get("/api/machines").session(s)).andReturn().getResponse()
                .getContentAsString()).findValues("id").get(0).asLong();
        long hp = jdbc.sql("select id from machine where protocol = 'LOCAL'").query(Long.class).single();
        assertThat(hp).isPositive();
        mvc.perform(get("/api/machines/" + hp + "/browse").param("path", "/home/teste").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parent").value("/home"))
                .andExpect(jsonPath("$.items[0].name").value("pasta-a"))
                .andExpect(jsonPath("$.items[?(@.name=='.oculto')]", hasSize(1)));
        mvc.perform(get("/api/machines/" + hp + "/browse").param("path", "/etc").session(s))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("local.fora-das-raizes"));
        mvc.perform(get("/api/machines/" + hp + "/browse").param("path", "/home/teste").param("onlyDirs", "true")
                .session(s)).andExpect(jsonPath("$.items[?(@.name=='.oculto')]").isEmpty());
        assertThat(local).isPositive();
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return List.of();
    }
}
