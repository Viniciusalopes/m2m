package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Remover uma máquina preserva os backups feitos a partir dela e os guardados nela (machine-connections). */
@IntegrationTest
class MaquinaRemovidaIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    Usuarios usuarios;
    @Autowired
    BackupArchiveRepository archives;

    MockHttpSession s;
    long dono;
    long nas;
    long deposito;

    @BeforeEach
    void preparar() throws Exception {
        Usuarios.Criado u = usuarios.novo();
        dono = u.id();
        s = (MockHttpSession) mvc.perform(post("/api/auth/login").param("username", u.username())
                .param("password", Usuarios.SENHA).with(csrf())).andReturn().getRequest().getSession(false);
        nas = criarMaquina("NAS");
        deposito = criarMaquina("DEPOSITO");
    }

    private long criarMaquina(String nome) throws Exception {
        String corpo = """
                {"name":"%s","host":"%s.exemplo.test","protocol":"SFTP","port":22,"username":"usuario",
                 "authType":"PASSWORD","password":"senha"}""".formatted(nome, nome.toLowerCase());
        String r = mvc.perform(post("/api/machines").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("id").asLong();
    }

    private long backup(Long perfil, long armazenamento, String nomeArmazenamento, String origem, String arquivo) {
        return jdbc.sql("""
                insert into backup_archive (owner_id, profile_id, profile_name_snapshot, type, status, accepted,
                       storage_machine_id, storage_machine_name, storage_dir, file_name, source_machine_name)
                values (:o, :p, 'fotos', 'REGULAR', 'COMPLETED', true, :m, :mn, '/backups', :f, :src)
                returning id""")
                .param("o", dono).param("p", perfil).param("m", armazenamento).param("mn", nomeArmazenamento)
                .param("f", arquivo).param("src", origem).query(Long.class).single();
    }

    private JsonNode item(long id) throws Exception {
        String r = mvc.perform(get("/api/backups").session(s).param("size", "200")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode n : json.readTree(r)) {
            if (n.get("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("backup " + id + " não listado");
    }

    @Test
    void removerAMaquinaPreservaOsBackupsEReligaAoCadastrarDeNovo() throws Exception {
        long guardado = backup(null, nas, "NAS", "OUTRA", "fotos_NAS_1.tar.zst");
        long feitoDela = backup(null, deposito, "DEPOSITO", "NAS", "fotos_NAS_2.tar.zst");

        // a confirmação mostra quantos backups estão ligados à máquina
        mvc.perform(get("/api/machines/" + nas + "/backups").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fromMachine").value(1)).andExpect(jsonPath("$.storedOnMachine").value(1));

        mvc.perform(delete("/api/machines/" + nas).session(s).with(csrf())).andExpect(status().isNoContent());

        // nada foi apagado: os dois backups continuam no catálogo
        assertThat(jdbc.sql("select count(*) from backup_archive where id in (:a, :b)").param("a", guardado)
                .param("b", feitoDela).query(Long.class).single()).isEqualTo(2);
        assertThat(item(guardado).get("storageAvailable").asBoolean()).isFalse();
        assertThat(item(guardado).get("storageMachineName").asText()).isEqualTo("NAS");
        assertThat(item(feitoDela).get("storageAvailable").asBoolean()).isTrue();
        assertThat(item(feitoDela).get("sourceMachineName").asText()).isEqualTo("NAS");

        // as ações sobre o arquivo são recusadas com o motivo, e excluir não remove o registro
        for (String caminho : List.of("/api/backups/" + guardado + "/download", "/api/backups/" + guardado + "/tree")) {
            mvc.perform(get(caminho).session(s)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("backup.armazenamento-indisponivel"))
                    .andExpect(jsonPath("$.parametros.maquina").value("NAS"));
        }
        mvc.perform(delete("/api/backups/" + guardado).session(s).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("backup.armazenamento-indisponivel"));
        assertThat(archives.find(dono, guardado)).isPresent();

        // a verificação periódica não o considera (não pode virar "ausente")
        assertThat(archives.verifiable(dono)).extracting(BackupArchive::id).contains(feitoDela)
                .doesNotContain(guardado);

        // cadastrar de novo uma máquina com o mesmo nome religa o backup
        long novoNas = criarMaquina("NAS");
        assertThat(item(guardado).get("storageAvailable").asBoolean()).isTrue();
        assertThat(archives.find(dono, guardado).orElseThrow().storageMachineId()).isEqualTo(novoNas);
    }

    @Test
    void aRetencaoIgnoraOsBackupsDeArmazenamentoRemovido() throws Exception {
        long perfil = jdbc.sql("""
                insert into profile (owner_id, name, source_machine_id, storage_machine_id, storage_dir)
                values (:o, 'fotos', :d, :d, '/backups') returning id""")
                .param("o", dono).param("d", deposito).query(Long.class).single();
        // o mais antigo ficou no NAS, antigo armazenamento do perfil
        long antigo = backup(perfil, nas, "NAS", "DEPOSITO", "fotos_1.tar.zst");
        long medio = backup(perfil, deposito, "DEPOSITO", "DEPOSITO", "fotos_2.tar.zst");
        long novo = backup(perfil, deposito, "DEPOSITO", "DEPOSITO", "fotos_3.tar.zst");
        mvc.perform(delete("/api/machines/" + nas).session(s).with(csrf())).andExpect(status().isNoContent());

        assertThat(archives.acceptedUnpinned(perfil, "REGULAR")).extracting(BackupArchive::id)
                .containsExactlyInAnyOrder(medio, novo).doesNotContain(antigo);
    }
}
