package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@Import(BackupTestSupport.class)
class BackupGestaoIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    BackupTestSupport t;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Usuarios usuarios;

    MockHttpSession admin;

    @BeforeEach
    void entrar() throws Exception {
        admin = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "Senha-Admin-Teste-1").with(csrf())).andReturn().getRequest().getSession(false);
    }

    private static String nome() {
        return "g-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private BackupTestSupport.Sandbox comDados() throws Exception {
        var sb = t.novoSandbox();
        Files.createDirectories(sb.origem().resolve("sub/fundo"));
        Files.writeString(sb.origem().resolve("raiz.txt"), "raiz");
        Files.writeString(sb.origem().resolve(".oculto"), "x");
        Files.writeString(sb.origem().resolve("sub/a.txt"), "a");
        Files.writeString(sb.origem().resolve("sub/fundo/b.txt"), "b");
        return sb;
    }

    @Test
    void listaComFiltrosEEstados() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "um");
        mvc.perform(get("/api/backups").param("profileId", String.valueOf(perfil)).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("COMPLETED")).andExpect(jsonPath("$[0].orphan").value(false))
                .andExpect(jsonPath("$[0].fileCount").value(4)).andExpect(jsonPath("$[0].pinned").value(false));
        mvc.perform(get("/api/backups").param("profileId", String.valueOf(perfil)).param("status", "FAILED")
                .session(admin)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/backups").param("type", "REGULAR").param("profileId", String.valueOf(perfil)).session(admin))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void verConteudoPelaArvoreDoManifestoLateral() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        long id = t.arquivos(perfil).get(0).id();

        mvc.perform(get("/api/backups/" + id + "/tree").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("dir-1"))
                .andExpect(jsonPath("$.items[0].link").value(sb.origemPath()));
        mvc.perform(get("/api/backups/" + id + "/tree").param("path", "dir-1").session(admin))
                .andExpect(jsonPath("$.total").value(3)) // sub/, .oculto e raiz.txt
                .andExpect(jsonPath("$.items[0].name").value("sub"))
                .andExpect(jsonPath("$.items[0].type").value("DIR"))
                .andExpect(jsonPath("$.items[*].name").value(hasItem(".oculto")))
                .andExpect(jsonPath("$.items[*].name").value(hasItem("raiz.txt")));
        mvc.perform(get("/api/backups/" + id + "/tree").param("path", "dir-1/sub").session(admin))
                .andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("fundo", "a.txt")))
                .andExpect(jsonPath("$.parent").value("dir-1"));
        mvc.perform(get("/api/backups/" + id + "/tree").param("path", "dir-1/sub/fundo").param("size", "1")
                .session(admin)).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/backups/" + id + "/tree").param("path", "invalido").session(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void baixarOArquivoCompletoEPorFaixa() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        BackupArchive a = t.arquivos(perfil).get(0);
        Path arq = sb.armazenamento().resolve(a.fileName());
        byte[] original = Files.readAllBytes(arq);

        MvcResult completo = mvc.perform(get("/api/backups/" + a.id() + "/download").session(admin))
                .andExpect(request().asyncStarted()).andReturn();
        byte[] baixado = mvc.perform(asyncDispatch(completo)).andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + a.fileName() + "\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(baixado).isEqualTo(original);

        MvcResult faixa = mvc.perform(get("/api/backups/" + a.id() + "/download").header("Range", "bytes=10-49")
                .session(admin)).andExpect(request().asyncStarted()).andReturn();
        byte[] parte = mvc.perform(asyncDispatch(faixa)).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 10-49/" + original.length)).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(parte).isEqualTo(java.util.Arrays.copyOfRange(original, 10, 50));

        mvc.perform(get("/api/backups/" + a.id() + "/download").header("Range", "bytes=999999999-")
                .session(admin)).andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(get("/api/audit").param("action", "BACKUP_DOWNLOAD").session(admin))
                .andExpect(jsonPath("$.items[0].target").value(a.fileName()));
    }

    @Test
    void fixarEDesafixarEntramNaAuditoria() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        long id = t.arquivos(perfil).get(0).id();
        mvc.perform(post("/api/backups/" + id + "/pin").session(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(post("/api/backups/" + id + "/unpin").session(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false));
        mvc.perform(get("/api/audit").param("action", "BACKUP_PIN").session(admin)).andExpect(status().isOk());
    }

    @Test
    void backupFixadoFicaForaDaRetencao() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "a");
        long a = t.arquivos(perfil).get(0).id();
        mvc.perform(post("/api/backups/" + a + "/pin").session(admin).with(csrf())).andExpect(status().isOk());
        t.executar(perfil, "b");
        t.executar(perfil, "c");
        // A (fixado) + B + C
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).hasSize(3);
        assertThat(lista).extracting(BackupArchive::id).contains(a);
        t.executar(perfil, "d"); // B sai; A continua
        List<BackupArchive> depois = t.arquivos(perfil);
        assertThat(depois).hasSize(3);
        assertThat(depois).extracting(BackupArchive::fileName).noneMatch(n -> n.contains("_b_"));
        assertThat(depois).extracting(BackupArchive::id).contains(a);
    }

    @Test
    void aceitarSuspeitoAplicaARetencao() throws Exception {
        var sb = t.novoSandbox();
        for (int i = 0; i < 10; i++) {
            Files.writeString(sb.origem().resolve("f" + i), "conteudo " + i);
        }
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, "a");
        t.executar(perfil, "b");
        for (int i = 1; i < 10; i++) {
            Files.delete(sb.origem().resolve("f" + i));
        }
        t.executar(perfil, "c");
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).hasSize(3);
        long suspeito = lista.get(2).id();
        assertThat(lista.get(2).status()).isEqualTo(ArchiveStatus.SUSPECT);

        mvc.perform(post("/api/backups/" + suspeito + "/accept").session(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.accepted").value(true));
        // retenção aplicada: o mais antigo (a) saiu
        assertThat(t.arquivos(perfil)).extracting(BackupArchive::fileName).noneMatch(n -> n.contains("_a_"))
                .hasSize(2);
        // aceitar algo que não é suspeito é recusado
        mvc.perform(post("/api/backups/" + suspeito + "/accept").session(admin).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("backup.nao-esta-suspeito"));
    }

    @Test
    void excluirRemoveArquivoManifestoERegistro() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        BackupArchive a = t.arquivos(perfil).get(0);
        mvc.perform(delete("/api/backups/" + a.id()).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(sb.armazenamento().resolve(a.fileName())).doesNotExist();
        assertThat(sb.armazenamento().resolve(a.fileName() + ".manifest")).doesNotExist();
        mvc.perform(get("/api/backups/" + a.id()).session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void excluirBackupEmUsoPorJobEhRecusado() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        BackupArchive a = t.arquivos(perfil).get(0);
        long job = jdbc.sql("""
                insert into job (owner_id, type, status, trigger, params, started_at)
                values (:o, 'RESTORE', 'RUNNING', 'MANUAL', cast(:p as jsonb), now()) returning id""")
                .param("o", t.adminId()).param("p", "{\"archiveId\":" + a.id() + "}").query(Long.class).single();
        try {
            mvc.perform(delete("/api/backups/" + a.id()).session(admin).with(csrf())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("backup.em-uso"));
            assertThat(sb.armazenamento().resolve(a.fileName())).exists();
        } finally {
            jdbc.sql("update job set status = 'COMPLETED', finished_at = now() where id = :id").param("id", job).update();
        }
    }

    @Test
    void cadaUsuarioVeApenasSeusBackups() throws Exception {
        var sb = comDados();
        long perfil = t.criarPerfil(sb, nome());
        t.executar(perfil, null);
        long id = t.arquivos(perfil).get(0).id();
        var outro = usuarios.novo();
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", outro.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/backups/" + id).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/backups/" + id + "/tree").session(s)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/backups/" + id).session(s).with(csrf())).andExpect(status().isNotFound());
    }
}
