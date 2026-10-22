package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.profile.BackupScheduler;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import br.com.vovolinux.m2m.support.TestHandlers;
import br.com.vovolinux.m2m.support.Usuarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Perfis sobre a máquina LOCAL do seed (host falso em target/test-hostfs) e um servidor SFTP em container. */
@IntegrationTest
@Import(TestHandlers.class)
class PerfisIT {

    private static final Path HOST = Path.of("target/test-hostfs/home/teste");
    private static OpenSshServer ssh;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    BackupScheduler scheduler;
    @Autowired
    Usuarios usuarios;

    MockHttpSession admin;
    long local;
    long dell;
    String base; // diretório exclusivo deste teste, no "host"

    @BeforeAll
    static void subir() {
        ssh = OpenSshServer.start();
    }

    @AfterAll
    static void parar() {
        ssh.stop();
    }

    @BeforeEach
    void preparar() throws Exception {
        admin = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
        local = jdbc.sql("select id from machine where protocol = 'LOCAL'").query(Long.class).single();
        dell = jdbc.sql("select id from machine where name = 'NOTEBOOK-REMOTO'").query(Long.class).single();
        String id = UUID.randomUUID().toString().substring(0, 8);
        base = "/home/teste/t-" + id;
        Files.createDirectories(HOST.resolve("t-" + id + "/trabalho"));
        Files.createDirectories(HOST.resolve("t-" + id + "/fotos"));
        Files.writeString(HOST.resolve("t-" + id + "/trabalho/a.txt"), "a");
    }

    private String perfil(String nome, String dirs, String storagePath, String extra) {
        return """
                {"name":"%s","sourceMachineId":%d,"directories":%s,
                 "storage":{"machineId":%d,"path":"%s"}%s}""".formatted(nome, local, dirs, local, storagePath, extra);
    }

    private String umDir() {
        return "[{\"path\":\"" + base + "/trabalho\"}]";
    }

    private JsonNode criar(String corpo) throws Exception {
        MvcResult r = mvc.perform(post("/api/profiles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void rejeita(String corpo, int http, String codigo) throws Exception {
        mvc.perform(post("/api/profiles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().is(http)).andExpect(jsonPath("$.codigo").value(codigo));
    }

    private String nome() {
        return "perfil-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void criaPerfilEOferecePadroesDeArmazenamento() throws Exception {
        mvc.perform(get("/api/profiles/defaults").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.storageMachineId").value(local))
                .andExpect(jsonPath("$.storageDir").value("/home/backup"))
                .andExpect(jsonPath("$.suspectThreshold").value(0.5));

        JsonNode p = criar(perfil(nome(), umDir(), base + "/backups", ""));
        assertThat(p.get("sourceMachineName").asText()).isEqualTo("NOTEBOOK");
        assertThat(p.get("storage").get("path").asText()).isEqualTo(base + "/backups");
        assertThat(p.get("suspectThreshold").asDouble()).isEqualTo(0.5);
        assertThat(p.get("directories").get(0).get("path").asText()).isEqualTo(base + "/trabalho");
        // o armazenamento foi criado e testado, sem deixar arquivo de teste
        try (var s = Files.list(HOST.resolve(base.substring("/home/teste/".length()) + "/backups"))) {
            assertThat(s.count()).isZero();
        }
    }

    @Test
    void nomeDuplicadoEhRejeitado() throws Exception {
        String n = nome();
        criar(perfil(n, umDir(), base + "/b1", ""));
        rejeita(perfil(n.toUpperCase(), umDir(), base + "/b2", ""), 409, "perfil.nome-duplicado");
    }

    @Test
    void diretorioInexistenteRaizESobrepostos() throws Exception {
        rejeita(perfil(nome(), "[{\"path\":\"" + base + "/nao-existe\"}]", base + "/b", ""), 400,
                "perfil.diretorio-inexistente");
        rejeita(perfil(nome(), "[{\"path\":\"/\"}]", base + "/b", ""), 400, "perfil.diretorio-raiz");
        rejeita(perfil(nome(), "[{\"path\":\"/home\"}]", base + "/b", ""), 400, "perfil.diretorio-raiz");
        // igual, pai e filho
        Files.createDirectories(HOST.resolve(base.substring("/home/teste/".length()) + "/trabalho/sub"));
        rejeita(perfil(nome(), "[{\"path\":\"" + base + "/trabalho\"},{\"path\":\"" + base + "/trabalho\"}]",
                base + "/b", ""), 400, "perfil.diretorios-sobrepostos");
        rejeita(perfil(nome(), "[{\"path\":\"" + base + "/trabalho\"},{\"path\":\"" + base + "/trabalho/sub\"}]",
                base + "/b", ""), 400, "perfil.diretorios-sobrepostos");
        // dois diretórios independentes são aceitos
        criar(perfil(nome(), "[{\"path\":\"" + base + "/trabalho\"},{\"path\":\"" + base + "/fotos\"}]", base + "/b",
                ""));
    }

    @Test
    void armazenamentoNaoPodeFicarDentroDaOrigem() throws Exception {
        rejeita(perfil(nome(), umDir(), base + "/trabalho/backups", ""), 400, "perfil.armazenamento-dentro-da-origem");
        rejeita(perfil(nome(), umDir(), base + "/trabalho", ""), 400, "perfil.armazenamento-dentro-da-origem");
    }

    @Test
    void armazenamentoSemPermissaoDeEscritaEhRejeitado() throws Exception {
        Path ro = HOST.resolve(base.substring("/home/teste/".length()) + "/somente-leitura");
        Files.createDirectories(ro);
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            rejeita(perfil(nome(), umDir(), base + "/somente-leitura/dentro", ""), 400,
                    "perfil.armazenamento-sem-permissao");
        } finally {
            Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void complementoSoAceitaLetrasNumerosHifenESublinhado() throws Exception {
        rejeita(perfil(nome(), umDir(), base + "/b", ",\"complement\":\"versão final/1\""), 400,
                "perfil.complemento-invalido");
        JsonNode ok = criar(perfil(nome(), umDir(), base + "/b", ",\"complement\":\"versao_final-1\""));
        assertThat(ok.get("complement").asText()).isEqualTo("versao_final-1");
    }

    @Test
    void limiarDeSuspeitoPorPerfil() throws Exception {
        JsonNode p = criar(perfil(nome(), umDir(), base + "/b", ",\"suspectThreshold\":0.3"));
        assertThat(p.get("suspectThreshold").asDouble()).isEqualTo(0.3);
        mvc.perform(post("/api/profiles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(perfil(nome(), umDir(), base + "/b", ",\"suspectThreshold\":0"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("validacao.invalida"));
    }

    @Test
    void mapeamentoPorMaquinaDeDestino() throws Exception {
        String dirs = "[{\"path\":\"" + base + "/trabalho\",\"mappings\":[{\"machineId\":" + dell
                + ",\"path\":\"/home/dell/projetos\"}]},{\"path\":\"" + base + "/fotos\",\"mappings\":[{\"machineId\":"
                + dell + ",\"path\":\"/home/dell/fotos\"}]}]";
        JsonNode p = criar(perfil(nome(), dirs, base + "/b", ""));
        assertThat(p.get("directories").get(0).get("mappings").get(0).get("path").asText())
                .isEqualTo("/home/dell/projetos");
        assertThat(p.get("directories").get(1).get("mappings").get(0).get("machineId").asLong()).isEqualTo(dell);

        // destinos que se sobrepõem na mesma máquina são recusados
        String sobrepostos = "[{\"path\":\"" + base + "/trabalho\",\"mappings\":[{\"machineId\":" + dell
                + ",\"path\":\"/home/dell/x\"}]},{\"path\":\"" + base + "/fotos\",\"mappings\":[{\"machineId\":" + dell
                + ",\"path\":\"/home/dell/x/fotos\"}]}]";
        rejeita(perfil(nome(), sobrepostos, base + "/b", ""), 400, "perfil.mapeamento-sobreposto");
        // mapeamento relativo é inválido
        rejeita(perfil(nome(), "[{\"path\":\"" + base + "/trabalho\",\"mappings\":[{\"machineId\":" + dell
                + ",\"path\":\"relativo\"}]}]", base + "/b", ""), 400, "perfil.mapeamento-invalido");
    }

    @Test
    void agendamentoDeBackupCriaEDesfazOGatilho() throws Exception {
        JsonNode p = criar(perfil(nome(), umDir(), base + "/b", ",\"backupSchedule\":{\"time\":\"02:00\",\"enabled\":true}"));
        long id = p.get("id").asLong();
        assertThat(p.get("backupSchedule").get("time").asText()).isEqualTo("02:00");
        assertThat(scheduler.isScheduled(id)).isTrue();

        mvc.perform(put("/api/profiles/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(perfil(p.get("name").asText(), umDir(), base + "/b",
                        ",\"backupSchedule\":{\"time\":\"03:30\",\"enabled\":false}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.backupSchedule.enabled").value(false));
        assertThat(scheduler.isScheduled(id)).isFalse();

        rejeita(perfil(nome(), umDir(), base + "/b", ",\"backupSchedule\":{\"time\":\"25:99\",\"enabled\":true}"), 400,
                "perfil.horario-invalido");
    }

    @Test
    void disparoAgendadoEntraNaFilaComoAgendado() throws Exception {
        JsonNode p = criar(perfil(nome(), umDir(), base + "/b", ",\"backupSchedule\":{\"time\":\"02:00\",\"enabled\":true}"));
        long id = p.get("id").asLong();
        java.lang.reflect.Method fire = BackupScheduler.class.getDeclaredMethod("fire", long.class);
        fire.setAccessible(true);
        fire.invoke(scheduler, id);
        assertThat(jdbc.sql("select count(*) from job where profile_id = :p and trigger = 'SCHEDULED' and type = 'BACKUP'")
                .param("p", id).query(Long.class).single()).isEqualTo(1);
        // o motor de backup só chega no grupo 8: sem handler o job falha com um código claro (ainda sem duplicar)
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jdbc
                .sql("select status from job where profile_id = :p").param("p", id).query(String.class).single()
                .equals("FAILED"));
    }

    @Test
    void backupManualComJobAtivoRetorna409ComOJobEmAndamento() throws Exception {
        JsonNode p = criar(perfil(nome(), umDir(), base + "/b", ""));
        long id = p.get("id").asLong();
        // ocupa a fila com um job de teste que não termina
        long ocupa = jdbc.sql("""
                insert into job (owner_id, type, status, trigger, params, started_at)
                values ((select id from app_user where username = 'admin'), 'TEST', 'RUNNING', 'MANUAL',
                        '{"name":"x"}'::jsonb, now()) returning id""").query(Long.class).single();
        try {
            mvc.perform(post("/api/profiles/" + id + "/backup").session(admin).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"complement\":\"pontual\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("job.em-andamento"))
                    .andExpect(jsonPath("$.parametros.jobId").value(ocupa));
            mvc.perform(post("/api/profiles/" + id + "/backup").session(admin).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"complement\":\"versão final/1\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("perfil.complemento-invalido"));
        } finally {
            jdbc.sql("update job set status = 'COMPLETED', finished_at = now() where id = :id").param("id", ocupa).update();
        }
    }

    @Test
    void excluirPerfilMantendoOsBackupsOsDeixaOrfaos() throws Exception {
        String n = nome();
        JsonNode p = criar(perfil(n, umDir(), base + "/b", ",\"backupSchedule\":{\"time\":\"02:00\",\"enabled\":true}"));
        long id = p.get("id").asLong();
        long arq = inserirBackup(id, n, "orfao.tar.zst");

        mvc.perform(delete("/api/profiles/" + id).param("keepBackups", "true").session(admin).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(scheduler.isScheduled(id)).isFalse();
        assertThat(jdbc.sql("select count(*) from backup_schedule where profile_id = :p").param("p", id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select profile_id from backup_archive where id = :a").param("a", arq)
                .query(Long.class).optional()).isEmpty().isNotNull(); // linha existe, profile_id nulo
        assertThat(jdbc.sql("select count(*) from backup_archive where id = :a and profile_id is null")
                .param("a", arq).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select profile_name_snapshot from backup_archive where id = :a").param("a", arq)
                .query(String.class).single()).isEqualTo(n);
    }

    @Test
    void excluirPerfilApagandoOsBackupsRemoveOsArquivos() throws Exception {
        String n = nome();
        long id = criar(perfil(n, umDir(), base + "/arquivos", "")).get("id").asLong();
        long arq = inserirBackup(id, n, "apagar.tar.zst");
        Path dir = HOST.resolve(base.substring("/home/teste/".length()) + "/arquivos");
        Files.writeString(dir.resolve("apagar.tar.zst"), "x");
        Files.writeString(dir.resolve("apagar.tar.zst.manifest"), "m");

        mvc.perform(delete("/api/profiles/" + id).param("keepBackups", "false").session(admin).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(Files.exists(dir.resolve("apagar.tar.zst"))).isFalse();
        assertThat(Files.exists(dir.resolve("apagar.tar.zst.manifest"))).isFalse();
        assertThat(jdbc.sql("select count(*) from backup_archive where id = :a").param("a", arq).query(Long.class)
                .single()).isZero();
    }

    @Test
    void excluirPerfilComJobNaFilaEhRecusado() throws Exception {
        long id = criar(perfil(nome(), umDir(), base + "/b", "")).get("id").asLong();
        long job = jdbc.sql("""
                insert into job (owner_id, type, status, trigger, profile_id, params)
                values ((select id from app_user where username = 'admin'), 'TEST', 'QUEUED', 'SCHEDULED', :p,
                        '{"name":"x"}'::jsonb) returning id""").param("p", id).query(Long.class).single();
        try {
            mvc.perform(delete("/api/profiles/" + id).session(admin).with(csrf())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("perfil.job-ativo"));
        } finally {
            jdbc.sql("update job set status = 'CANCELED', finished_at = now() where id = :id").param("id", job).update();
        }
        mvc.perform(delete("/api/profiles/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void maquinaUsadaPorPerfilNaoPodeSerRemovida() throws Exception {
        // o storage remoto: máquina SFTP em container com a identidade confirmada
        MvcResult m = mvc.perform(post("/api/machines").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Remota-%s","host":"%s","protocol":"SFTP","port":%d,"username":"m2m",
                         "authType":"PASSWORD","password":"m2m-teste"}""".formatted(UUID.randomUUID().toString()
                        .substring(0, 6), ssh.host(), ssh.port())))
                .andExpect(status().isCreated()).andReturn();
        long remota = json.readTree(m.getResponse().getContentAsString()).get("id").asLong();
        MvcResult t = mvc.perform(post("/api/machines/" + remota + "/test").session(admin).with(csrf())).andReturn();
        String fp = json.readTree(t.getResponse().getContentAsString()).get("fingerprint").asText();
        mvc.perform(post("/api/machines/" + remota + "/identity/confirm").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + fp + "\"}"))
                .andExpect(status().isNoContent());

        // armazenamento remoto (VPS) aceito, com teste de escrita feito lá
        String n = nome();
        MvcResult r = mvc.perform(post("/api/profiles").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","sourceMachineId":%d,"directories":%s,
                         "storage":{"machineId":%d,"path":"/config/backups/m2m"}}""".formatted(n, local, umDir(), remota)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.storageMachineName").value(org.hamcrest.Matchers.startsWith("Remota-")))
                .andReturn();
        long perfilId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        assertThat(Files.isDirectory(ssh.homeOnHost().resolve("backups/m2m"))).isTrue();

        mvc.perform(delete("/api/machines/" + remota).session(admin).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("maquina.em-uso"))
                .andExpect(jsonPath("$.parametros.usos").value(hasItem("Perfil " + n + " (armazenamento)")));

        mvc.perform(delete("/api/profiles/" + perfilId).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/machines/" + remota).session(admin).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void cadaUsuarioVeApenasSeusPerfis() throws Exception {
        long id = criar(perfil(nome(), umDir(), base + "/b", "")).get("id").asLong();
        var outro = usuarios.novo();
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", outro.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/profiles/" + id).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/profiles").session(s)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/profiles/" + id).session(s).with(csrf())).andExpect(status().isNotFound());
    }

    private long inserirBackup(long profileId, String profileName, String fileName) {
        return jdbc.sql("""
                insert into backup_archive (owner_id, profile_id, profile_name_snapshot, type, status, accepted,
                       storage_machine_id, storage_machine_name, storage_dir, file_name)
                values ((select id from app_user where username = 'admin'), :p, :n, 'REGULAR', 'COMPLETED', true,
                        :m, 'NOTEBOOK', :d, :f) returning id""")
                .param("p", profileId).param("n", profileName).param("m", local).param("d", base + "/arquivos")
                .param("f", fileName).query(Long.class).single();
    }
}
