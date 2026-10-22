package br.com.vovolinux.m2m.backup;

import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Cenário de teste de backup: perfil sobre o "host" falso, execução de jobs e conferência de resultado. */
@Component
public class BackupTestSupport {

    public static final Path HOST = Path.of("target/test-hostfs/home/teste");

    private final ProfileService profiles;
    private final JobRepository jobs;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public BackupTestSupport(ProfileService profiles, JobRepository jobs,
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.profiles = profiles;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    public long adminId() {
        return jdbc.sql("select id from app_user where username = 'admin'").query(Long.class).single();
    }

    public long localMachineId() {
        return jdbc.sql("select id from machine where protocol = 'LOCAL'").query(Long.class).single();
    }

    /** Diretório exclusivo do teste: {origem, armazenamento} no host falso. */
    public record Sandbox(String id, Path origem, Path armazenamento, String origemPath, String armazenamentoPath) {
    }

    public Sandbox novoSandbox() throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path base = HOST.resolve("b-" + id);
        Files.createDirectories(base.resolve("origem"));
        return new Sandbox(id, base.resolve("origem"), base.resolve("backups"), "/home/teste/b-" + id + "/origem",
                "/home/teste/b-" + id + "/backups");
    }

    public long criarPerfil(Sandbox sb, String nome, String complemento, double limiar, long storageMachineId,
            String storagePath) {
        return profiles.create(adminId(), new ProfileDtos.ProfileRequest(nome, complemento, localMachineId(),
                List.of(new ProfileDtos.Dir(sb.origemPath(), List.of())),
                new ProfileDtos.Storage(storageMachineId, storagePath), limiar, false, null)).id();
    }

    public long criarPerfil(Sandbox sb, String nome) {
        return criarPerfil(sb, nome, null, 0.5, localMachineId(), sb.armazenamentoPath());
    }

    /** Inicia o backup manual e espera o job terminar. */
    public Job executar(long profileId, String complemento) {
        awaitIdle();
        Job job = profiles.startBackup(adminId(), profileId, complemento);
        return esperar(job.id());
    }

    public Job esperar(long jobId) {
        await().atMost(Duration.ofSeconds(120)).until(() -> JobStatus.isFinal(jobs.findAny(jobId).orElseThrow().status()));
        return jobs.findAny(jobId).orElseThrow();
    }

    public void awaitIdle() {
        await().atMost(Duration.ofSeconds(60)).until(() -> jobs.active().isEmpty());
    }

    /** Cria uma máquina SFTP (senha) apontando para o container e confirma a identidade do servidor. */
    public long maquinaSftp(String nome, String host, int port, String user, String password,
            br.com.vovolinux.m2m.machine.MachineService machines, br.com.vovolinux.m2m.machine.ConnectionTester tester,
            br.com.vovolinux.m2m.machine.ServerIdentityService identities) {
        var view = machines.create(adminId(), new br.com.vovolinux.m2m.machine.MachineDtos.MachineRequest(nome, null,
                host, br.com.vovolinux.m2m.machine.Protocol.SFTP, port, null, user, "PASSWORD", password, null));
        var machine = machines.require(adminId(), view.id());
        var r = tester.test(machine);
        identities.confirm(view.id(), nome, r.fingerprint());
        return view.id();
    }

    public List<BackupArchive> arquivos(long profileId) {
        return jdbc.sql("select " + BackupArchiveRepository.COLUMNS + " from backup_archive where profile_id = :p order by id")
                .param("p", profileId).query(BackupArchiveRepository::map).list();
    }
}
