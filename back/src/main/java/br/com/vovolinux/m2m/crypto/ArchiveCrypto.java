package br.com.vovolinux.m2m.crypto;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Criptografia dos backups (D29): Tink {@code StreamingAead} (AES-256-GCM-HKDF, segmentos de 1 MiB), autenticada e em
 * streaming. Uma chave por perfil, guardada cifrada pela chave mestra; o arquivo gravado é {@code .tar.zst.enc}.
 */
@Component
public class ArchiveCrypto {

    /** Dados associados: ligam o texto cifrado ao formato do m2m (o comando {@code decrypt} usa o mesmo valor). */
    public static final byte[] ASSOCIATED_DATA = "m2m-archive-v1".getBytes(StandardCharsets.UTF_8);
    public static final String TEMPLATE = "AES256_GCM_HKDF_1MB";
    public static final String EXPORT_FORMAT = "m2m-chave-de-perfil";

    /** Uma chave pronta para cifrar e decifrar. */
    public record Key(String keyId, StreamingAead aead) {
    }

    /** Falha ao localizar ou usar a chave de um backup. */
    public static class KeyUnavailable extends RuntimeException {
        public KeyUnavailable(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final CryptoService master;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Key> cache = new ConcurrentHashMap<>();

    public ArchiveCrypto(JdbcClient jdbc, CryptoService master, JsonMapper json) {
        this.jdbc = jdbc;
        this.master = master;
        this.json = json;
    }

    @PostConstruct
    void init() throws GeneralSecurityException {
        StreamingAeadConfig.register();
    }

    // ---- chaves ----

    private Key open(String keyId, byte[] keysetEnc) {
        try {
            KeysetHandle handle = TinkProtoKeysetFormat.parseKeyset(master.decrypt(keysetEnc),
                    InsecureSecretKeyAccess.get());
            return new Key(keyId, handle.getPrimitive(RegistryConfiguration.get(), StreamingAead.class));
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailable("Chave " + keyId + " ilegível: " + e.getMessage());
        }
    }

    /** A chave ativa do perfil; cria uma nova se o perfil ainda não tem. */
    public Key forProfile(long ownerId, long profileId, String profileName) {
        var existing = jdbc.sql("select key_id, keyset_enc from profile_key where profile_id = :p and active")
                .param("p", profileId).query((rs, i) -> new Object[] {rs.getString(1), rs.getBytes(2)}).optional();
        if (existing.isPresent()) {
            String id = (String) existing.get()[0];
            return cache.computeIfAbsent(id, k -> open(k, (byte[]) existing.get()[1]));
        }
        try {
            KeysetHandle handle = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE));
            byte[] keyset = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
            byte[] raw = new byte[8];
            random.nextBytes(raw);
            String keyId = HexFormat.of().formatHex(raw);
            jdbc.sql("""
                    insert into profile_key (key_id, owner_id, profile_id, profile_name, keyset_enc)
                    values (:k, :o, :p, :n, :e)""").param("k", keyId).param("o", ownerId).param("p", profileId)
                    .param("n", profileName).param("e", master.encrypt(keyset)).update();
            Key key = new Key(keyId, handle.getPrimitive(RegistryConfiguration.get(), StreamingAead.class));
            cache.put(keyId, key);
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível gerar a chave de criptografia", e);
        }
    }

    public boolean profileHasKey(long profileId) {
        return jdbc.sql("select count(*) from profile_key where profile_id = :p").param("p", profileId)
                .query(Long.class).single() > 0;
    }

    /** A chave com esse id (a que cifrou um backup). */
    public Key byId(String keyId) {
        if (keyId == null) {
            throw new KeyUnavailable("O backup criptografado não tem chave registrada");
        }
        Key cached = cache.get(keyId);
        if (cached != null) {
            return cached;
        }
        byte[] enc = jdbc.sql("select keyset_enc from profile_key where key_id = :k").param("k", keyId)
                .query(byte[].class).optional()
                .orElseThrow(() -> new KeyUnavailable("A chave " + keyId + " não existe mais"));
        return cache.computeIfAbsent(keyId, k -> open(k, enc));
    }

    /** Todas as chaves do usuário (para reindexar arquivos cuja chave não se conhece). */
    public List<Key> keysOf(long ownerId) {
        return jdbc.sql("select key_id from profile_key where owner_id = :o order by id").param("o", ownerId)
                .query(String.class).list().stream().map(this::byId).toList();
    }

    // ---- fluxos ----

    public OutputStream encrypt(Key key, OutputStream out) throws IOException {
        try {
            return key.aead().newEncryptingStream(out, ASSOCIATED_DATA);
        } catch (GeneralSecurityException e) {
            throw new IOException("Falha ao iniciar a cifragem", e);
        }
    }

    public InputStream decrypt(Key key, InputStream in) throws IOException {
        try {
            return key.aead().newDecryptingStream(in, ASSOCIATED_DATA);
        } catch (GeneralSecurityException e) {
            throw new IOException("Falha ao iniciar a decifragem (chave errada ou arquivo corrompido)", e);
        }
    }

    // ---- exportação ----

    /**
     * Arquivo de exportação da chave de um perfil: o keyset do Tink em JSON, em claro, mais metadados. Quem o possui
     * decifra os backups do perfil; por isso a exportação é auditada e a interface avisa.
     */
    public Optional<String> exportKey(long ownerId, long profileId) {
        return jdbc.sql("select key_id, profile_name, keyset_enc from profile_key where owner_id = :o and profile_id = :p and active")
                .param("o", ownerId).param("p", profileId)
                .query((rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBytes(3)}).optional()
                .map(r -> {
                    try {
                        KeysetHandle handle = TinkProtoKeysetFormat.parseKeyset(master.decrypt((byte[]) r[2]),
                                InsecureSecretKeyAccess.get());
                        var root = json.createObjectNode();
                        root.put("formato", EXPORT_FORMAT);
                        root.put("keyId", (String) r[0]);
                        root.put("perfil", (String) r[1]);
                        root.put("modelo", TEMPLATE);
                        root.put("keyset", TinkJsonProtoKeysetFormat.serializeKeyset(handle,
                                InsecureSecretKeyAccess.get()));
                        return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
                    } catch (GeneralSecurityException e) {
                        throw new IllegalStateException("Não foi possível exportar a chave", e);
                    }
                });
    }
}
