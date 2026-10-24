package br.com.vovolinux.m2m.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.Usuarios;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rotas internas do instalador: token, origem, exportação e importação da configuração com as chaves dos perfis. */
@IntegrationTest
class InstaladorIT {

    private static final String TOKEN = InstallerController.token("chave-mestra-de-teste-com-mais-de-16-caracteres");
    private static final String SENHA = "senha-da-reinstalacao";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    Usuarios usuarios;
    @Autowired
    PasswordCipher cipher;

    private ResultActions chamar(String rota, Map<String, Object> corpo, String origem) throws Exception {
        return mvc.perform(post("/internal/instalador" + rota).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)).with(r -> {
                    r.setRemoteAddr(origem);
                    return r;
                }));
    }

    private Map<String, Object> pedido(String usuario) {
        Map<String, Object> p = new HashMap<>();
        p.put("token", TOKEN);
        p.put("usuario", usuario);
        p.put("senha", SENHA);
        return p;
    }

    @Test
    void tokenIgualAoDoInstalador() {
        // o mesmo vetor está no teste do instalador (installer/internal/instalacao/back_test.go)
        assertThat(TOKEN).isEqualTo("9de52f59c11500db1701c04ff2110fcdb3694910df0c06b344e13aeaa1bdf47d");
    }

    @Test
    void recusaTokenErradoEOrigemDeFora() throws Exception {
        Usuarios.Criado u = usuarios.novo();
        Map<String, Object> errado = pedido(u.username());
        errado.put("token", "0".repeat(64));
        chamar("/config/export", errado, "127.0.0.1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("instalador.acesso-negado"));
        chamar("/config/export", pedido(u.username()), "172.18.0.5").andExpect(status().isForbidden());
        chamar("/config/export", pedido("ninguem"), "127.0.0.1").andExpect(status().isNotFound());
    }

    @Test
    void exportaEImportaAConfiguracaoComAsChavesDosPerfis() throws Exception {
        Usuarios.Criado origem = usuarios.novo();
        long deposito = jdbc.sql("""
                insert into machine (owner_id, name, host, protocol, port, username, auth_type, max_connections)
                values (:o, 'DEPOSITO', 'deposito.exemplo.test', 'SFTP', 22, 'usuario', 'KEY', 4) returning id""")
                .param("o", origem.id()).query(Long.class).single();
        long perfil = jdbc.sql("""
                insert into profile (owner_id, name, source_machine_id, storage_machine_id, storage_dir, encryption_enabled)
                values (:o, 'fotos', :d, :d, '/backups', true) returning id""")
                .param("o", origem.id()).param("d", deposito).query(Long.class).single();
        String keyId = "chave-" + System.nanoTime();
        byte[] keyset = "keyset-de-teste".getBytes();
        jdbc.sql("""
                insert into profile_key (key_id, owner_id, profile_id, profile_name, keyset_enc)
                values (:k, :o, :p, 'fotos', :e)""").param("k", keyId).param("o", origem.id()).param("p", perfil)
                .param("e", master(keyset)).update();

        String r = chamar("/config/export", pedido(origem.username()), "127.0.0.1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conteudo = json.readTree(r).get("conteudo").asText();
        JsonNode aberto = json.readTree(cipher.decrypt(conteudo, SENHA.toCharArray()));
        assertThat(aberto.get("chavesPerfis").get(0).get("idChave").asText()).isEqualTo(keyId);
        assertThat(Base64.getDecoder().decode(aberto.get("chavesPerfis").get(0).get("chave").asText())).isEqualTo(keyset);

        // como numa reinstalação: banco sem a chave, outro usuário recebe a configuração
        jdbc.sql("delete from profile_key where key_id = :k").param("k", keyId).update();
        Usuarios.Criado destino = usuarios.novo();
        Map<String, Object> imp = pedido(destino.username());
        imp.put("conteudo", conteudo);
        chamar("/config/import", imp, "127.0.0.1").andExpect(status().isOk())
                .andExpect(jsonPath("$.machinesCreate[0]").value("DEPOSITO"))
                .andExpect(jsonPath("$.profilesCreate[0]").value("fotos"));
        Map<String, Object> chave = jdbc.sql("""
                select k.active, p.name from profile_key k join profile p on p.id = k.profile_id
                 where k.key_id = :k and k.owner_id = :o""").param("k", keyId).param("o", destino.id()).query()
                .singleRow();
        assertThat(chave.get("active")).isEqualTo(true);
        assertThat(chave.get("name")).isEqualTo("fotos");
    }

    @Autowired
    br.com.vovolinux.m2m.crypto.CryptoService crypto;

    private byte[] master(byte[] claro) {
        return crypto.encrypt(claro);
    }
}
