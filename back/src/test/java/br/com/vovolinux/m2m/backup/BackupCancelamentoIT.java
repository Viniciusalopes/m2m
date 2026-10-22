package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.RunningJobs;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(BackupTestSupport.class)
class BackupCancelamentoIT {

    @Autowired
    BackupTestSupport t;
    @Autowired
    ProfileService profiles;
    @Autowired
    JobService jobs;
    @Autowired
    RunningJobs running;

    @Test
    void emergenciaDuranteOBackupRemoveOParcialEMantemOsAnteriores() throws Exception {
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("pequeno.txt"), "p");
        long perfil = t.criarPerfil(sb, "canc-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(t.executar(perfil, "anterior").status()).isEqualTo(JobStatus.COMPLETED);

        // 400 MiB incompressíveis: tempo de sobra para interromper no meio da gravação
        Path grande = sb.origem().resolve("grande.bin");
        Random rnd = new Random(7);
        byte[] bloco = new byte[1 << 20];
        try (OutputStream out = Files.newOutputStream(grande)) {
            for (int i = 0; i < 400; i++) {
                rnd.nextBytes(bloco);
                out.write(bloco);
            }
        }

        t.awaitIdle();
        Job job = profiles.startBackup(t.adminId(), perfil, "interrompido");
        await().atMost(Duration.ofSeconds(60)).until(() -> running.get(job.id())
                .map(r -> "BACKING_UP".equals(r.progress().step()) && ((Number) r.progress().snapshot().get("bytesDone")).longValue() > 0)
                .orElse(false));

        long antes = System.nanoTime();
        jobs.cancel(t.adminId(), job.id());
        Job fim = t.esperar(job.id());
        assertThat(Duration.ofNanos(System.nanoTime() - antes)).isLessThan(Duration.ofSeconds(10));

        assertThat(fim.status()).isEqualTo(JobStatus.INTERRUPTED);
        List<BackupArchive> lista = t.arquivos(perfil);
        assertThat(lista).extracting(BackupArchive::status).containsExactly(ArchiveStatus.COMPLETED,
                ArchiveStatus.INTERRUPTED);
        // sem .partial, sem arquivo do backup interrompido e o anterior intacto
        try (Stream<Path> s = Files.list(sb.armazenamento())) {
            List<String> nomes = s.map(p -> p.getFileName().toString()).toList();
            assertThat(nomes).noneMatch(n -> n.endsWith(".partial"));
            assertThat(nomes.stream().filter(n -> n.endsWith(".tar.zst"))).hasSize(1);
        }
        assertThat(Files.exists(Path.of("target/test-tmp/job-" + job.id()))).isFalse();
    }
}
