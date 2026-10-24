package br.com.vovolinux.m2m.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Servidor OpenSSH completo em container (sem chroot, com shell): permite testar escrita na home, canal exec
 * (hash remoto) e extensões do SFTP, como numa máquina real.
 */
public final class OpenSshServer {

    public static final String USER = "m2m";
    public static final String PASSWORD = "m2m-teste";

    private final GenericContainer<?> container;
    private final Path configDir;

    private OpenSshServer(GenericContainer<?> container, Path configDir) {
        this.container = container;
        this.configDir = configDir;
    }

    public static OpenSshServer start() {
        try {
            Path config = Files.createTempDirectory("m2m-openssh-config-");
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwxrwxrwx"));
            // o OpenSSH 9.8+ penaliza a origem que desconecta antes de autenticar, e o teste de conexão faz isso de
            // propósito para capturar a chave do servidor: com todos os testes vindo do mesmo endereço, o sshd passaria
            // a recusar as conexões no meio da classe. A imagem inclui o sshd_config.d se a pasta existir.
            Path drop = Files.createDirectories(config.resolve("sshd/sshd_config.d"));
            Files.writeString(drop.resolve("m2m-testes.conf"), "PerSourcePenalties no\n");
            // o container passa /config para PUID:PGID; com o usuário de quem roda os testes, o host continua gravando
            // nele (o runner do GitHub não é o 1000 das máquinas de desenvolvimento)
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("lscr.io/linuxserver/openssh-server:latest"))
                    .withEnv("PUID", HostUser.uid(config)).withEnv("PGID", HostUser.gid(config)).withEnv("TZ", "America/Sao_Paulo")
                    .withEnv("USER_NAME", USER).withEnv("USER_PASSWORD", PASSWORD).withEnv("PASSWORD_ACCESS", "true")
                    .withExposedPorts(2222)
                    .withFileSystemBind(config.toString(), "/config")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(90)));
            c.start();
            return new OpenSshServer(c, config);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível subir o servidor OpenSSH de teste", e);
        }
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return container.getMappedPort(2222);
    }

    /** A home do usuário (/config no container) vista pelo host do teste. */
    public Path homeOnHost() {
        return configDir;
    }

    public void stop() {
        container.stop();
        try (var s = Files.walk(configDir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignorado) {
            // limpeza best-effort
        }
    }
}
