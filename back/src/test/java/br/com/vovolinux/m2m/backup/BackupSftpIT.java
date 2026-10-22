package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.machine.ConnectionTester;
import br.com.vovolinux.m2m.machine.MachineService;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.profile.ProfileDtos;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.support.IntegrationTest;
import br.com.vovolinux.m2m.support.OpenSshServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Backups com origem e com armazenamento em servidor SFTP real (container), nunca em máquinas reais (D19). */
@IntegrationTest
@Import(BackupTestSupport.class)
class BackupSftpIT {

    private static OpenSshServer ssh;

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

    @BeforeAll
    static void subir() {
        ssh = OpenSshServer.start();
    }

    @AfterAll
    static void parar() {
        ssh.stop();
    }

    private long servidor() {
        return t.maquinaSftp("Servidor-" + UUID.randomUUID().toString().substring(0, 6), ssh.host(), ssh.port(),
                OpenSshServer.USER, OpenSshServer.PASSWORD, machines, tester, identities);
    }

    @Test
    void backupDeOrigemSftpPreservaPermissoesELinks() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path remoto = ssh.homeOnHost().resolve("origem-" + id);
        Files.createDirectories(remoto.resolve("v2"));
        Files.writeString(remoto.resolve("build.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(remoto.resolve("build.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(remoto.resolve("v2/ok.txt"), "v2");
        Files.writeString(remoto.resolve(".oculto"), "x");
        // "v2/" com a barra final: o Java a normalizaria, então o link é criado pelo ln
        new ProcessBuilder("ln", "-s", "v2/", remoto.resolve("atual").toString()).inheritIO().start().waitFor();
        new ProcessBuilder("mkfifo", remoto.resolve("fila").toString()).inheritIO().start().waitFor();

        long maquina = servidor();
        var sb = t.novoSandbox();
        long perfil = profiles.create(t.adminId(), new ProfileDtos.ProfileRequest("sftp-" + id, null, maquina,
                List.of(new ProfileDtos.Dir("/config/origem-" + id, List.of())),
                new ProfileDtos.Storage(t.localMachineId(), sb.armazenamentoPath()), 0.5, false, null)).id();

        Job job = t.executar(perfil, null);
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_WARNINGS); // o FIFO virou aviso
        BackupArchive a = t.arquivos(perfil).get(0);
        assertThat(a.sourceProtocol()).isEqualTo("SFTP");
        assertThat(a.fullFidelity()).isTrue();
        assertThat(a.warnings()).contains("fila");

        Path out = sb.armazenamento().resolveSibling("extraido");
        BackupIT.extrairComTar(sb.armazenamento().resolve(a.fileName()), out);
        Path d = out.resolve("dir-1");
        assertThat(Files.getPosixFilePermissions(d.resolve("build.sh")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(d.resolve(".oculto")).exists();
        assertThat(Files.isSymbolicLink(d.resolve("atual"))).isTrue();
        assertThat(Files.readSymbolicLink(d.resolve("atual")).toString()).isEqualTo("v2/");
        assertThat(d.resolve("v2/ok.txt")).hasContent("v2");
    }

    @Test
    void armazenamentoRemotoGravaPartialValidaERenomeia() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var sb = t.novoSandbox();
        Files.writeString(sb.origem().resolve("a.txt"), "conteúdo para a VPS");
        long vps = servidor();
        long perfil = t.criarPerfil(sb, "vps-" + id, null, 0.5, vps, "/config/backups/m2m-" + id);

        Job job = t.executar(perfil, null);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        BackupArchive a = t.arquivos(perfil).get(0);
        Path naVps = ssh.homeOnHost().resolve("backups/m2m-" + id);
        assertThat(naVps.resolve(a.fileName())).exists();
        assertThat(naVps.resolve(a.fileName() + ".manifest")).exists();
        try (var s = Files.list(naVps)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".partial"));
        }
        // o arquivo gravado remotamente é um tar.zst válido
        Path out = sb.armazenamento().resolveSibling("extraido-vps");
        BackupIT.extrairComTar(naVps.resolve(a.fileName()), out);
        assertThat(out.resolve("dir-1/a.txt")).hasContent("conteúdo para a VPS");
        assertThat(a.storageMachineName()).startsWith("Servidor-");
    }
}
