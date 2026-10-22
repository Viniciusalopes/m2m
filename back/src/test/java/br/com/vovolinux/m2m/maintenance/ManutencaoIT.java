package br.com.vovolinux.m2m.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupTestSupport;
import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.MachineDtos;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.notification.NotificationRepository;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.web.ApiException;
import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verificação periódica, reindexação, backup do banco e exportação/importação; só LOCAL em sandbox (D19). */
@IntegrationTest
@Import({BackupTestSupport.class, ManutencaoIT.Dump.class})
class ManutencaoIT {

    /** O pg_dump roda dentro do container do Postgres do teste (o host não tem o cliente instalado). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Dump {
        @Bean
        @Primary
        PgDumpRunner pgDumpDoContainer(PostgreSQLContainer pg) {
            return new PgDumpRunner("jdbc:postgresql://x:5432/x", "u", "p", "pg_dump") {
                @Override
                public void dump(OutputStream out) throws IOException {
                    try {
                        var r = pg.execInContainer("pg_dump", "--no-owner", "--no-privileges", "-U", pg.getUsername(),
                                "-d", pg.getDatabaseName());
                        if (r.getExitCode() != 0) {
                            throw new IOException(r.getStderr());
                        }
                        out.write(r.getStdout().getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
            };
        }
    }

    @Autowired
    BackupTestSupport t;
    @Autowired
    JobService jobs;
    @Autowired
    JobRepository jobRepo;
    @Autowired
    ProfileService profiles;
    @Autowired
    MachineService machines;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    MaintenanceSettings settings;
    @Autowired
    ConfigTransferService config;
    @Autowired
    CryptoService crypto;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PostgreSQLContainer postgres;

    Job rodar(String tipo, Map<String, Object> params) {
        t.awaitIdle();
        return t.esperar(jobs.enqueue(t.adminId(), tipo, "MANUAL", null, null, params).id());
    }

    BackupTestSupport.Sandbox backupComUmArquivo(String nome, long[] profileOut) throws IOException {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("dado.txt"), "conteúdo ".repeat(2000));
        long perfil = t.criarPerfil(sb, nome);
        profileOut[0] = perfil;
        Job j = t.executar(perfil, null);
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        return sb;
    }

    Path arquivoDe(BackupTestSupport.Sandbox sb, BackupArchive a) {
        return sb.armazenamento().resolve(a.fileName());
    }

    @Test
    void verificacaoDetectaCorrompidoEAusente() throws IOException {
        long[] p = new long[1];
        var sb = backupComUmArquivo("Verif-" + UUID.randomUUID().toString().substring(0, 6), p);
        BackupArchive a = t.arquivos(p[0]).get(0);

        Job ok = rodar(JobType.ARCHIVE_VERIFY, Map.of());
        assertThat(ok.status()).isEqualTo(JobStatus.COMPLETED);

        try (RandomAccessFile f = new RandomAccessFile(arquivoDe(sb, a).toFile(), "rw")) {
            long meio = f.length() / 2;
            f.seek(meio);
            int b = f.read();
            f.seek(meio);
            f.write(b ^ 0xFF);
        }
        Job corrompido = rodar(JobType.ARCHIVE_VERIFY, Map.of());
        assertThat(corrompido.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS);
        assertThat(t.arquivos(p[0]).get(0).status()).isEqualTo(ArchiveStatus.CORRUPTED);
        assertThat(notifications.list(t.adminId(), false, 100, 0)).anyMatch(
                n -> n.type().equals("BACKUP_CORRUPTED") && n.params().contains(a.fileName()));

        Files.delete(arquivoDe(sb, a));
        rodar(JobType.ARCHIVE_VERIFY, Map.of());
        assertThat(t.arquivos(p[0]).get(0).status()).isEqualTo(ArchiveStatus.MISSING);
        assertThat(notifications.list(t.adminId(), false, 100, 0)).anyMatch(
                n -> n.type().equals("BACKUP_MISSING") && n.params().contains(a.fileName()));
    }

    @Test
    void reindexacaoRecriaRegistrosDoBancoVazioEMarcaAusentes() throws IOException {
        long[] p = new long[1];
        var sb = backupComUmArquivo("Reind-" + UUID.randomUUID().toString().substring(0, 6), p);
        BackupArchive original = t.arquivos(p[0]).get(0);
        jdbc.sql("delete from backup_archive where id = :id").param("id", original.id()).update();
        assertThat(t.arquivos(p[0])).isEmpty();

        Map<String, Object> params = Map.of("machineId", t.localMachineId(), "dir", sb.armazenamentoPath());
        Job j = rodar(JobType.REINDEX, params);
        assertThat(j.status()).isIn(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS);
        List<BackupArchive> recriados = t.arquivos(p[0]);
        assertThat(recriados).hasSize(1);
        BackupArchive r = recriados.get(0);
        assertThat(r.fileName()).isEqualTo(original.fileName());
        assertThat(r.fileCount()).isEqualTo(original.fileCount());
        assertThat(r.itemCount()).isEqualTo(original.itemCount());
        assertThat(r.status()).isEqualTo(ArchiveStatus.COMPLETED);

        // sem o manifesto lateral, lê o interno
        Files.deleteIfExists(sb.armazenamento().resolve(original.fileName() + ".manifest"));
        jdbc.sql("delete from backup_archive where id = :id").param("id", r.id()).update();
        rodar(JobType.REINDEX, params);
        assertThat(t.arquivos(p[0])).hasSize(1);

        // arquivo apagado por fora → ausente
        Files.delete(arquivoDe(sb, original));
        rodar(JobType.REINDEX, params);
        assertThat(t.arquivos(p[0]).get(0).status()).isEqualTo(ArchiveStatus.MISSING);
    }

    @Test
    void dumpDoBancoERestauracaoNumBancoNovo() throws Exception {
        String dir = "db-" + UUID.randomUUID().toString().substring(0, 6);
        settings.saveDbBackup(new MaintenanceSettings.DbBackup(true, "01:30", null, "/home/teste/" + dir));
        Job j = rodar(JobType.DB_BACKUP, Map.of());
        assertThat(j.status()).isEqualTo(JobStatus.COMPLETED);

        Path pasta = BackupTestSupport.HOST.resolve(dir);
        Path dump;
        try (Stream<Path> s = Files.list(pasta)) {
            List<Path> arquivos = s.filter(x -> x.getFileName().toString().endsWith(".sql.zst")).toList();
            assertThat(arquivos).hasSize(1);
            dump = arquivos.get(0);
        }
        byte[] sql;
        try (var in = new ZstdInputStream(Files.newInputStream(dump))) {
            sql = in.readAllBytes();
        }
        assertThat(new String(sql, StandardCharsets.UTF_8)).contains("CREATE TABLE public.app_user");

        try (PostgreSQLContainer novo = new PostgreSQLContainer("postgres:18")) {
            novo.start();
            novo.copyFileToContainer(Transferable.of(sql), "/tmp/dump.sql");
            var restore = novo.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", novo.getUsername(), "-d",
                    novo.getDatabaseName(), "-f", "/tmp/dump.sql");
            assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
            var usuarios = novo.execInContainer("psql", "-tA", "-U", novo.getUsername(), "-d", novo.getDatabaseName(),
                    "-c", "select count(*) from app_user");
            assertThat(usuarios.getStdout().trim()).isEqualTo(String.valueOf(
                    jdbc.sql("select count(*) from app_user").query(Long.class).single()));
        }
    }

    @Test
    void dumpMantemSoOsSeteMaisRecentes() throws Exception {
        String dir = "db-ret-" + UUID.randomUUID().toString().substring(0, 6);
        Path pasta = BackupTestSupport.HOST.resolve(dir);
        Files.createDirectories(pasta);
        for (int i = 1; i <= 9; i++) {
            Files.writeString(pasta.resolve("m2m-db_2020010" + i + "-000000.sql.zst"), "x");
        }
        settings.saveDbBackup(new MaintenanceSettings.DbBackup(true, "01:30", null, "/home/teste/" + dir));
        rodar(JobType.DB_BACKUP, Map.of());
        try (Stream<Path> s = Files.list(pasta)) {
            assertThat(s.filter(x -> x.getFileName().toString().endsWith(".sql.zst")).count()).isEqualTo(7);
        }
        assertThat(Files.exists(pasta.resolve("m2m-db_20200101-000000.sql.zst"))).isFalse();
    }

    @Test
    void exportarEImportarComSenhaCertaEErrada() throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 6);
        long owner = t.adminId();
        var maq = machines.create(owner, new MachineDtos.MachineRequest("Cfg-" + id, null, "localhost",
                Protocol.SFTP, 2222, null, "usuario", "PASSWORD", "segredo-da-maquina", null));
        Files.createDirectories(BackupTestSupport.HOST.resolve("cfg-" + id));
        var sb = new String[] {"/home/teste/cfg-" + id};
        long perfil = profiles.create(owner, new ProfileDtos.ProfileRequest("Perfil-" + id, null, t.localMachineId(),
                List.of(new ProfileDtos.Dir(sb[0], List.of(new ProfileDtos.Mapping(maq.id(), "/dest/" + id)))),
                new ProfileDtos.Storage(t.localMachineId(), sb[0] + "-bk"), 0.5, false,
                new ProfileDtos.Schedule("02:15", true))).id();

        String arquivo = config.export(owner, "senha-forte-123");
        assertThat(arquivo).doesNotContain("segredo-da-maquina").doesNotContain("Cfg-" + id);

        assertThatThrownBy(() -> config.preview(owner, arquivo, "senha-errada-999")).isInstanceOf(ApiException.class)
                .hasMessageContaining("senha-incorreta");
        assertThatThrownBy(() -> config.apply(owner, arquivo, "senha-errada-999")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> config.export(owner, "curta")).isInstanceOf(ApiException.class)
                .hasMessageContaining("senha-curta");
        assertThatThrownBy(() -> config.preview(owner, "{\"x\":1}", "senha-forte-123"))
                .isInstanceOf(ApiException.class).hasMessageContaining("arquivo-invalido");

        var previa = config.preview(owner, arquivo, "senha-forte-123");
        assertThat(previa.machinesReplace()).contains("Cfg-" + id);
        assertThat(previa.profilesReplace()).contains("Perfil-" + id);

        // perde tudo e importa de novo
        profiles.delete(owner, perfil, true);
        machines.delete(owner, maq.id());
        assertThat(jdbc.sql("select count(*) from machine where name = :n").param("n", "Cfg-" + id).query(Long.class)
                .single()).isZero();
        var aplicada = config.apply(owner, arquivo, "senha-forte-123");
        assertThat(aplicada.machinesCreate()).contains("Cfg-" + id);
        assertThat(aplicada.profilesCreate()).contains("Perfil-" + id);

        byte[] enc = jdbc.sql("select password_enc from machine where name = :n").param("n", "Cfg-" + id)
                .query(byte[].class).single();
        assertThat(crypto.decryptString(enc)).isEqualTo("segredo-da-maquina");
        assertThat(jdbc.sql("""
                select count(*) from profile p join backup_schedule b on b.profile_id = p.id
                 where p.name = :n and b.time_of_day = '02:15'""").param("n", "Perfil-" + id).query(Long.class).single())
                .isEqualTo(1);
    }
}
