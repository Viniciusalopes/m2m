package br.com.vovolinux.m2m.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.files.SpaceInfo;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.profile.BackupScheduler;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.SmtpServer;
import br.com.vovolinux.m2m.support.TestHandlers;
import br.com.vovolinux.m2m.support.Usuarios;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
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

@IntegrationTest
@Import({BackupTestSupport.class, TestHandlers.class})
class NotificacoesIT {

    private static SmtpServer smtp;

    @Autowired
    MockMvc mvc;
    @Autowired
    BackupTestSupport t;
    @Autowired
    NotificationService notifications;
    @Autowired
    NotificationRepository repository;
    @Autowired
    SpaceMonitor spaceMonitor;
    @Autowired
    JobService jobs;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Usuarios usuarios;
    @Autowired
    BackupScheduler scheduler;

    MockHttpSession s;
    Usuarios.Criado user;

    @BeforeAll
    static void subir() {
        smtp = SmtpServer.start();
    }

    @AfterAll
    static void parar() {
        smtp.stop();
    }

    @BeforeEach
    void entrar() throws Exception {
        user = usuarios.novo();
        s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", user.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        smtp.clear();
    }

    private String smtpJson(String senha, boolean ativo, int porta) {
        return """
                {"host":"%s","port":%d,"security":"NONE","username":"usuario","password":"%s",
                 "fromAddress":"m2m@exemplo.test","enabled":%s}""".formatted(smtp.host(), porta, senha, ativo);
    }

    // ---- configuração de SMTP por usuário ----

    @Test
    void senhaDoSmtpFicaCifradaENuncaEhDevolvida() throws Exception {
        mvc.perform(get("/api/me/smtp").session(s)).andExpect(jsonPath("$.configured").value(false));
        String corpo = mvc.perform(put("/api/me/smtp").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("senha-secreta-smtp", true, smtp.smtpPort())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("senha-secreta-smtp");
        byte[] enc = jdbc.sql("select password_enc from user_smtp_setting where owner_id = :o").param("o", user.id())
                .query(byte[].class).single();
        assertThat(new String(enc)).doesNotContain("senha-secreta-smtp");
        // reabrir a seção: senha mascarada; campo vazio mantém a senha salva
        mvc.perform(get("/api/me/smtp").session(s)).andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("senha-secreta-smtp")))).andExpect(jsonPath("$.hasPassword").value(true));
        mvc.perform(put("/api/me/smtp").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("", true, smtp.smtpPort()))).andExpect(status().isOk());
        byte[] depois = jdbc.sql("select password_enc from user_smtp_setting where owner_id = :o").param("o", user.id())
                .query(byte[].class).single();
        assertThat(depois).isEqualTo(enc);
        // alteração auditada, sem a senha
        String audit = mvc.perform(get("/api/audit").param("action", "SMTP_UPDATE").session(s)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("SMTP_UPDATE").doesNotContain("senha-secreta-smtp");
    }

    @Test
    void emailDeTesteUsaOFormularioEMostraOErroDoServidor() throws Exception {
        mvc.perform(post("/api/me/smtp/test").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("x", false, smtp.smtpPort()))) // não precisa estar salvo nem ativo
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        await().atMost(Duration.ofSeconds(10)).until(() -> smtp.emails().contains(user.username() + "@exemplo.test"));
        assertThat(smtp.emails()).contains("E-mail de teste");

        mvc.perform(post("/api/me/smtp/test").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("x", false, 1))) // porta fechada
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ---- notificações ----

    @Test
    void notificacaoVaiParaOPainelEPorEmailQuandoOSmtpEstaAtivo() throws Exception {
        mvc.perform(put("/api/me/smtp").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("x", true, smtp.smtpPort()))).andExpect(status().isOk());
        long id = notifications.notify(user.id(), "BACKUP_SUSPECT", NotificationService.WARN,
                Map.of("arquivo", "a.tar.zst", "perfil", "trabalho", "arquivos", 300, "anteriores", 12000), "backup", 1L);

        await().atMost(Duration.ofSeconds(15)).until(() -> smtp.emails().contains("Backup suspeito"));
        String emails = smtp.emails();
        assertThat(emails).contains(user.username() + "@exemplo.test").contains("trabalho").contains("300");
        await().atMost(Duration.ofSeconds(5)).until(() -> "SENT".equals(
                jdbc.sql("select email_status from notification where id = :i").param("i", id).query(String.class)
                        .optional().orElse(null)));

        mvc.perform(get("/api/notifications").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1)).andExpect(jsonPath("$.items[0].type").value("BACKUP_SUSPECT"))
                .andExpect(jsonPath("$.items[0].params.arquivos").value(300));
    }

    @Test
    void semSmtpOuComEnvioDesativadoSoVaiParaOPainel() throws Exception {
        notifications.notify(user.id(), "BACKUP_SUSPECT", NotificationService.WARN, Map.of("arquivo", "x", "perfil", "p",
                "arquivos", 1, "anteriores", 2), null, null);
        mvc.perform(put("/api/me/smtp").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("x", false, smtp.smtpPort()))).andExpect(status().isOk());
        notifications.notify(user.id(), "LOW_SPACE", NotificationService.WARN, Map.of("maquina", "m", "dir", "/d",
                "percentual", "5,0", "livre", 1, "total", 20), null, null);
        Thread.sleep(1500);
        assertThat(smtp.emails()).doesNotContain(user.username());
        mvc.perform(get("/api/notifications").session(s)).andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void falhaNoEnvioDeEmailFicaRegistradaESemAfetarONada() throws Exception {
        mvc.perform(put("/api/me/smtp").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(smtpJson("x", true, 1))).andExpect(status().isOk()); // porta inválida
        long id = notifications.notify(user.id(), "DB_BACKUP_FAILED", NotificationService.ERROR,
                Map.of("motivo", "disco cheio"), null, null);
        assertThat(id).isPositive(); // a notificação foi criada normalmente
        await().atMost(Duration.ofSeconds(20)).until(() -> "FAILED".equals(
                jdbc.sql("select email_status from notification where id = :i").param("i", id).query(String.class)
                        .optional().orElse(null)));
        assertThat(jdbc.sql("select email_error from notification where id = :i").param("i", id).query(String.class)
                .single()).isNotBlank();
        mvc.perform(get("/api/notifications").session(s)).andExpect(jsonPath("$.items[0].emailStatus").value("FAILED"));
    }

    @Test
    void marcarComoLidaEIsolamentoEntreUsuarios() throws Exception {
        long a = notifications.notify(user.id(), "LOW_SPACE", NotificationService.WARN, Map.of("maquina", "m", "dir", "/d",
                "percentual", "5", "livre", 1, "total", 20), null, null);
        notifications.notify(user.id(), "LOW_SPACE", NotificationService.WARN, Map.of("maquina", "m", "dir", "/e",
                "percentual", "5", "livre", 1, "total", 20), null, null);
        mvc.perform(get("/api/notifications/unread-count").session(s)).andExpect(jsonPath("$.unread").value(2));
        mvc.perform(post("/api/notifications/" + a + "/read").session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").session(s)).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/api/notifications").param("unreadOnly", "true").session(s)).andExpect(jsonPath("$.items", hasSize(1)));

        var outro = usuarios.novo();
        MockHttpSession so = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", outro.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/notifications/" + a + "/read").session(so).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications").session(so)).andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(post("/api/notifications/read-all").session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").session(s)).andExpect(jsonPath("$.unread").value(0));
    }

    // ---- gatilhos ----

    @Test
    void falhaDeJobAgendadoNotificaMasManualNao() throws Exception {
        t.awaitIdle();
        Job manual = jobs.enqueue(user.id(), "TEST", "MANUAL", null, "perfil-x", Map.of("name", "m1", "mode", "fail"));
        t.esperar(manual.id());
        Job agendado = jobs.enqueue(user.id(), "TEST", "SCHEDULED", null, "perfil-x", Map.of("name", "m2", "mode", "fail"));
        t.esperar(agendado.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.countUnread(user.id()) >= 1);
        mvc.perform(get("/api/notifications").session(s)).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type").value("JOB_FAILED"))
                .andExpect(jsonPath("$.items[0].params.perfil").value("perfil-x"))
                .andExpect(jsonPath("$.items[0].targetId").value(agendado.id()));
    }

    @Test
    void backupSuspeitoGeraNotificacao() throws Exception {
        var sb = t.novoSandbox();
        for (int i = 0; i < 10; i++) {
            Files.writeString(sb.origem().resolve("f" + i), "c" + i);
        }
        long perfil = t.criarPerfil(sb, "sp-" + UUID.randomUUID().toString().substring(0, 6));
        t.executar(perfil, "a");
        for (int i = 2; i < 10; i++) {
            Files.delete(sb.origem().resolve("f" + i));
        }
        Job job = t.executar(perfil, "b");
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        MockHttpSession admin = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/notifications").session(admin)).andExpect(jsonPath("$.items[*].type").value(hasItem("BACKUP_SUSPECT")));
    }

    @Test
    void espacoAbaixoDeDezPorCentoNotificaNoMaximoUmaVezPorDia() throws Exception {
        assertThat(spaceMonitor.evaluate(user.id(), "NOTEBOOK", "/home/backup", new SpaceInfo(20, 100))).isFalse();
        assertThat(spaceMonitor.evaluate(user.id(), "NOTEBOOK", "/home/backup", new SpaceInfo(10, 100))).isFalse(); // 10% não é < 10%
        assertThat(spaceMonitor.evaluate(user.id(), "NOTEBOOK", "/home/backup", new SpaceInfo(9, 100))).isTrue();
        assertThat(spaceMonitor.evaluate(user.id(), "NOTEBOOK", "/home/backup", new SpaceInfo(5, 100))).isFalse(); // já avisou hoje
        assertThat(spaceMonitor.evaluate(user.id(), "NOTEBOOK", "/outro", new SpaceInfo(5, 100))).isTrue(); // outro armazenamento
        mvc.perform(get("/api/notifications").session(s)).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].type").value(hasItem("LOW_SPACE")));
    }

    @Test
    void painelMostraPerfisAgendamentoEspacoEAtencao() throws Exception {
        var sb = t.novoSandbox();
        for (int i = 0; i < 10; i++) {
            Files.writeString(sb.origem().resolve("f" + i), "c" + i);
        }
        long perfil = t.criarPerfil(sb, "painel-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.sql("insert into backup_schedule (profile_id, time_of_day, enabled) values (:p, '02:00', true)")
                .param("p", perfil).update();
        scheduler.reschedule(perfil);
        t.executar(perfil, "a");
        t.executar(perfil, "b");
        for (int i = 2; i < 10; i++) {
            Files.delete(sb.origem().resolve("f" + i));
        }
        t.executar(perfil, "c"); // suspeito

        MockHttpSession admin = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/dashboard").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[?(@.id==" + perfil + ")].lastBackup.status").value(hasItem("SUSPECT")))
                .andExpect(jsonPath("$.profiles[?(@.id==" + perfil + ")].scheduled").value(hasItem(true)))
                .andExpect(jsonPath("$.profiles[?(@.id==" + perfil + ")].nextBackupAt").isNotEmpty())
                .andExpect(jsonPath("$.attention[*].status").value(hasItem("SUSPECT")))
                .andExpect(jsonPath("$.storage[*].state").value(hasItem("OK")))
                .andExpect(jsonPath("$.running").doesNotExist());
    }
}
