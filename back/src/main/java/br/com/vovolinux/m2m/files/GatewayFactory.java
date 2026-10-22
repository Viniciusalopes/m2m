package br.com.vovolinux.m2m.files;

import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.files.local.LocalFileGateway;
import br.com.vovolinux.m2m.files.sftp.SftpConnector;
import br.com.vovolinux.m2m.files.sftp.SftpFileGateway;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.ServerIdentityService;
import br.com.vovolinux.m2m.machine.SshIdentityService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Map;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Abre a conexão correta para o protocolo da máquina. */
@Component
public class GatewayFactory {

    private final LocalProperties local;
    private final SftpConnector sftp;
    private final CryptoService crypto;
    private final SshIdentityService sshIdentity;
    private final ServerIdentityService serverIdentity;
    private final JsonMapper json;

    public GatewayFactory(LocalProperties local, SftpConnector sftp, CryptoService crypto,
            SshIdentityService sshIdentity, ServerIdentityService serverIdentity, JsonMapper json) {
        this.local = local;
        this.sftp = sftp;
        this.crypto = crypto;
        this.sshIdentity = sshIdentity;
        this.serverIdentity = serverIdentity;
        this.json = json;
    }

    /** Capacidades gravadas pelo último teste de conexão (exec, statvfs, ...). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detected(boolean remoteHash, boolean freeSpace, boolean writable, boolean setsMtime) {
        static Detected none() {
            return new Detected(false, false, true, true);
        }
    }

    public Capabilities capabilitiesOf(Machine m) {
        Detected d = Detected.none();
        try {
            if (m.capabilitiesJson() != null && !m.capabilitiesJson().isBlank() && !m.capabilitiesJson().equals("{}")) {
                d = json.readValue(m.capabilitiesJson(), Detected.class);
            }
        } catch (RuntimeException e) {
            // capacidades ilegíveis: assume o mínimo até o próximo teste
        }
        return switch (m.protocol()) {
            case LOCAL -> Capabilities.full().withRemoteHash(true);
            case SFTP -> new Capabilities(true, true, true, d.remoteHash(), d.freeSpace(), d.writable());
            case FTP, FTPS -> new Capabilities(false, false, d.setsMtime(), false, false, d.writable());
        };
    }

    public FileGateway open(Machine m) throws IOException {
        switch (m.protocol()) {
            case LOCAL:
                if (!local.enabled()) {
                    throw new GatewayException("local.desativado", "O acesso à máquina LOCAL está desativado");
                }
                return new LocalFileGateway(Path.of(local.baseDir()), local.roots());
            case SFTP:
                return openSftp(m);
            default:
                throw new GatewayException("protocolo.nao-suportado", "Protocolo ainda não suportado: " + m.protocol(),
                        Map.of("protocolo", m.protocol().name()), null);
        }
    }

    private FileGateway openSftp(Machine m) throws IOException {
        KeyPair key = null;
        String password = null;
        try {
            if ("PASSWORD".equals(m.authType())) {
                password = crypto.decryptString(m.passwordEnc());
            } else {
                key = sshIdentity.privateKey(m.ownerId());
            }
        } catch (GeneralSecurityException e) {
            throw new GatewayException("credencial.invalida", "Não foi possível carregar a credencial", Map.of(), e);
        }
        ClientSession session = sftp.open(m.host(), m.port(), m.username(), key, password,
                serverIdentity.policy(m.id(), "SSH"));
        try {
            return new SftpFileGateway(session, capabilitiesOf(m));
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw e;
        }
    }
}
