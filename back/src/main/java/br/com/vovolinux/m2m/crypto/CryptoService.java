package br.com.vovolinux.m2m.crypto;

import br.com.vovolinux.m2m.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Cifragem AES-256-GCM com a chave mestra (APP_SECRET_KEY). Formato: versão(1) | iv(12) | texto cifrado + tag.
 * Cifra senhas de máquinas, chaves privadas e o segredo TOTP (D12).
 */
@Service
public class CryptoService {

    private static final byte VERSAO = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_SECRET_CHARS = 16;

    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();
    private SecretKey key;

    public CryptoService(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String secret = props.secretKey();
        if (secret == null || secret.length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException(
                    "APP_SECRET_KEY ausente ou curta (mínimo " + MIN_SECRET_CHARS + " caracteres): defina no .env");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain);
            return ByteBuffer.allocate(1 + IV_BYTES + enc.length).put(VERSAO).put(iv).put(enc).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar", e);
        }
    }

    public byte[] decrypt(byte[] payload) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            if (buf.get() != VERSAO) {
                throw new IllegalArgumentException("Versão de cifragem desconhecida");
            }
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] enc = new byte[buf.remaining()];
            buf.get(enc);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(enc);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar (chave mestra incorreta ou dado corrompido)", e);
        }
    }

    public byte[] encrypt(String plain) {
        return encrypt(plain.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] payload) {
        return new String(decrypt(payload), StandardCharsets.UTF_8);
    }
}
