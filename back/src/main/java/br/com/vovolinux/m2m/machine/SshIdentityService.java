package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.files.sftp.SftpConnector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Optional;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Chave SSH Ed25519 do usuário: a privada fica cifrada pela chave mestra; a pública é mostrada para autorização. */
@Service
public class SshIdentityService {

    public record PublicIdentity(String algorithm, String publicKey, String suggestedCommand) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public SshIdentityService(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** Devolve a identidade do usuário, gerando a chave na primeira vez. */
    public PublicIdentity ensure(long ownerId) {
        Optional<String> existing = jdbc.sql("select public_key from ssh_identity where owner_id = :o")
                .param("o", ownerId).query(String.class).optional();
        String pub = existing.orElseGet(() -> generate(ownerId));
        return new PublicIdentity("ssh-ed25519", pub, command(pub));
    }

    private String generate(long ownerId) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME);
            KeyPair kp = gen.generateKeyPair();
            ByteArrayOutputStream pem = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, "m2m", null, pem);
            String pub = PublicKeyEntry.toString(kp.getPublic()) + " m2m";
            jdbc.sql("""
                    insert into ssh_identity (owner_id, algorithm, public_key, private_key_enc)
                    values (:o, 'ED25519', :pub, :priv) on conflict (owner_id) do nothing""")
                    .param("o", ownerId).param("pub", pub).param("priv", crypto.encrypt(pem.toByteArray())).update();
            // em corrida, vale a chave que ficou gravada
            return jdbc.sql("select public_key from ssh_identity where owner_id = :o").param("o", ownerId)
                    .query(String.class).single();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Falha ao gerar a chave SSH", e);
        }
    }

    public KeyPair privateKey(long ownerId) throws IOException, GeneralSecurityException {
        ensure(ownerId);
        byte[] enc = jdbc.sql("select private_key_enc from ssh_identity where owner_id = :o").param("o", ownerId)
                .query(byte[].class).single();
        return SftpConnector.loadKey(new String(crypto.decrypt(enc), StandardCharsets.UTF_8));
    }

    static String command(String publicKey) {
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + publicKey
                + "' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys";
    }
}
