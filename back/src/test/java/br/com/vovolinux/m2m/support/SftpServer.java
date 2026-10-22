package br.com.vovolinux.m2m.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.io.ByteArrayOutputStream;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Servidor SFTP (OpenSSH) em container para os testes; o diretório "dados" é um bind mount do host do teste. */
public final class SftpServer {

    public static final String USER = "m2m";
    public static final String PASSWORD = "m2m-teste";

    private final GenericContainer<?> container;
    private final Path hostDir;
    private final Path keysDir;
    private final String privateKeyPem;

    private SftpServer(GenericContainer<?> container, Path hostDir, Path keysDir, String privateKeyPem) {
        this.container = container;
        this.hostDir = hostDir;
        this.keysDir = keysDir;
        this.privateKeyPem = privateKeyPem;
    }

    public static SftpServer start() {
        try {
            Path dados = Files.createTempDirectory("m2m-sftp-dados-",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
            Files.setPosixFilePermissions(dados, PosixFilePermissions.fromString("rwxrwxrwx"));
            Path keys = Files.createTempDirectory("m2m-sftp-keys-");

            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519", "BC");
            KeyPair kp = gen.generateKeyPair();
            ByteArrayOutputStream pem = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, "teste", null, pem);
            Files.writeString(keys.resolve("m2m.pub"), PublicKeyEntry.toString(kp.getPublic()) + " teste\n");
            Files.setPosixFilePermissions(keys.resolve("m2m.pub"), PostixReadable.perms());

            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:latest"))
                    .withCommand(USER + ":" + PASSWORD + ":1000:1000:dados")
                    .withExposedPorts(22)
                    .withFileSystemBind(dados.toString(), "/home/" + USER + "/dados")
                    .withFileSystemBind(keys.toString(), "/home/" + USER + "/.ssh/keys", org.testcontainers.containers.BindMode.READ_ONLY)
                    .waitingFor(Wait.forLogMessage(".*Server listening on.*", 1));
            c.start();
            return new SftpServer(c, dados, keys, pem.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível subir o servidor SFTP de teste", e);
        }
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return container.getMappedPort(22);
    }

    /** Diretório do host correspondente a {@code /dados} no servidor. */
    public Path hostDir() {
        return hostDir;
    }

    public String privateKeyPem() {
        return privateKeyPem;
    }

    public void stop() {
        container.stop();
        deleteQuietly(hostDir);
        deleteQuietly(keysDir);
    }

    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignorado) {
            // limpeza best-effort
        }
    }

    private static final class PostixReadable {
        static java.util.Set<java.nio.file.attribute.PosixFilePermission> perms() {
            return PosixFilePermissions.fromString("rw-r--r--");
        }
    }
}
