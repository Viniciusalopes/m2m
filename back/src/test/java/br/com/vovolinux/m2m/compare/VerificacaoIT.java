package br.com.vovolinux.m2m.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verificação de igualdade NOTEBOOK (LOCAL) × servidor SFTP em container, pelo job e pela API. */
@IntegrationTest
@Import(BackupTestSupport.class)
class VerificacaoIT {

    private static OpenSshServer ssh;

    @Autowired
    MockMvc mvc;
    @Autowired
    BackupTestSupport t;
    @Autowired
    MachineService machines;
    @Autowired
    ConnectionTester tester;
    @Autowired
    ServerIdentityService identities;
    @Autowired
    ProfileService profiles;
    @Autowired
    JsonMapper json;

    MockHttpSession admin;
    long vps;
    String id;
    Path origem;
    Path destino;
    long perfil;

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
        id = UUID.randomUUID().toString().substring(0, 8);
        var sb = t.novoSandbox();
        origem = sb.origem();
        destino = ssh.homeOnHost().resolve("dest-" + id);
        Files.createDirectories(destino);
        vps = t.maquinaSftp("VPS-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD,
                machines, tester, identities);
        perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("ver-" + id, null, t.localMachineId(),
                List.of(new ProfileDtos.Dir(sb.origemPath(),
                        List.of(new ProfileDtos.Mapping(vps, "/config/dest-" + id)))),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();
    }

    private static void escrever(Path p, String conteudo, long epochSeconds, String perm) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, conteudo);
        Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(epochSeconds)));
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perm));
    }

    private JsonNode verificar(String modo) throws Exception {
        t.awaitIdle();
        String resp = mvc.perform(post("/api/profiles/" + perfil + "/verify").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destMachineId\":" + vps + ",\"mode\":\"" + modo + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        Job job = t.esperar(json.readTree(resp).get("id").asLong());
        assertThat(job.status()).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
        return json.readTree(job.result());
    }

    @Test
    void diretoriosIdenticosIncluindoOcultosTemporariosEVazios() throws Exception {
        escrever(origem.resolve(".env"), "A=1", 1_700_000_000L, "rw-r--r--");
        escrever(origem.resolve("x.tmp"), "tmp", 1_700_000_100L, "rw-r--r--");
        escrever(origem.resolve("sub/b.txt"), "b", 1_700_000_200L, "rwxr-xr-x");
        Files.createDirectories(origem.resolve("vazia"));
        escrever(destino.resolve(".env"), "A=1", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("x.tmp"), "tmp", 1_700_000_100L, "rw-r--r--");
        escrever(destino.resolve("sub/b.txt"), "b", 1_700_000_200L, "rwxr-xr-x");
        Files.createDirectories(destino.resolve("vazia"));
        Files.setPosixFilePermissions(destino.resolve("vazia"), Files.getPosixFilePermissions(origem.resolve("vazia")));
        Files.setPosixFilePermissions(destino.resolve("sub"), Files.getPosixFilePermissions(origem.resolve("sub")));

        JsonNode r = verificar("FULL");
        assertThat(r.get("identical").asBoolean()).isTrue();
        assertThat(r.get("filesCompared").asLong()).isEqualTo(3);
        assertThat(r.get("notCompared").size()).isZero();
    }

    @Test
    void diferencasSaoListadasPorCategoria() throws Exception {
        escrever(origem.resolve("README.md"), "AAAA", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("README.md"), "BBBB", 1_700_000_000L, "rw-r--r--"); // mesmo tamanho e data
        escrever(destino.resolve(".env"), "so no destino", 1_700_000_000L, "rw-r--r--");
        escrever(origem.resolve("novo.txt"), "so na origem", 1_700_000_000L, "rw-r--r--");

        JsonNode rapido = verificar("QUICK");
        JsonNode dir = rapido.get("directories").get(0);
        assertThat(rapido.get("identical").asBoolean()).isFalse();
        assertThat(dir.get("onlyTarget").toString()).contains(".env");
        assertThat(dir.get("onlySource").toString()).contains("novo.txt");
        assertThat(dir.get("different").toString()).doesNotContain("README.md"); // rápido não vê o conteúdo

        JsonNode completo = verificar("FULL");
        assertThat(completo.get("directories").get(0).get("different").toString()).contains("README.md")
                .contains("CONTENT");
    }

    @Test
    void toleranciaDeDataEPermissoes() throws Exception {
        escrever(origem.resolve("a.txt"), "a", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("a.txt"), "a", 1_700_000_001L, "rw-r--r--"); // 1 s de diferença
        escrever(origem.resolve("b.sh"), "b", 1_700_000_000L, "rwxr-xr-x");
        escrever(destino.resolve("b.sh"), "b", 1_700_000_000L, "rw-r--r--"); // permissão diferente
        escrever(origem.resolve("c.txt"), "c", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("c.txt"), "c", 1_700_000_010L, "rw-r--r--"); // 10 s: fora da tolerância

        JsonNode r = verificar("QUICK").get("directories").get(0);
        String dif = r.get("different").toString();
        assertThat(dif).doesNotContain("a.txt");
        assertThat(dif).contains("b.sh").contains("MODE");
        assertThat(dif).contains("c.txt").contains("MTIME");
    }

    @Test
    void linksEEspeciais() throws Exception {
        escrever(origem.resolve("f.txt"), "f", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("f.txt"), "f", 1_700_000_000L, "rw-r--r--");
        Files.createSymbolicLink(origem.resolve("atual"), Path.of("v1"));
        Files.createSymbolicLink(destino.resolve("atual"), Path.of("v2"));
        new ProcessBuilder("mkfifo", origem.resolve("fila").toString()).inheritIO().start().waitFor();
        assertThat(verificar("QUICK").get("identical").asBoolean()).isTrue(); // alvo do link só no completo
        JsonNode r = verificar("FULL");
        assertThat(r.get("identical").asBoolean()).isFalse();
        assertThat(r.get("directories").get(0).get("different").toString()).contains("atual").contains("LINK");
        assertThat(r.get("specialsIgnored").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void destinoSemMapeamentoEhRecusadoEOsCandidatosSaoListados() throws Exception {
        long outra = t.maquinaSftp("Outra-" + id, ssh.host(), ssh.port(), OpenSshServer.USER, OpenSshServer.PASSWORD,
                machines, tester, identities);
        mvc.perform(post("/api/profiles/" + perfil + "/verify").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destMachineId\":" + outra + ",\"mode\":\"QUICK\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("verificacao.sem-mapeamento"));
        mvc.perform(get("/api/profiles/" + perfil + "/verify-targets").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem("VPS-" + id)))
                .andExpect(jsonPath("$[?(@.name=='Outra-" + id + "')]").isEmpty());
    }

    @Test
    void verificacaoNaoAlteraNada() throws Exception {
        escrever(origem.resolve("a.txt"), "a", 1_700_000_000L, "rw-r--r--");
        escrever(destino.resolve("b.txt"), "b", 1_700_000_000L, "rw-r--r--");
        verificar("FULL");
        assertThat(Files.readString(origem.resolve("a.txt"))).isEqualTo("a");
        assertThat(Files.exists(destino.resolve("a.txt"))).isFalse();
        assertThat(Files.readString(destino.resolve("b.txt"))).isEqualTo("b");
        assertThat(Files.getLastModifiedTime(destino.resolve("b.txt")).toInstant())
                .isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }
}
