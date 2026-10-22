package br.com.vovolinux.m2m.maintenance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cifra um texto com uma senha: Argon2id deriva a chave, AES-256-GCM cifra (D32). Resultado: envelope JSON. */
@Component
public class PasswordCipher {

    public static final String FORMAT = "m2m-config";
    public static final int VERSION = 1;
    private static final int MEMORY_KIB = 65536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;

    /** Senha incorreta ou arquivo adulterado: a etiqueta do GCM não confere. */
    public static class WrongPassword extends RuntimeException {
        public WrongPassword() {
            super("senha incorreta ou arquivo adulterado");
        }
    }

    /** O conteúdo não é um arquivo de configuração do m2m. */
    public static class InvalidFile extends RuntimeException {
        public InvalidFile(String message) {
            super(message);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json;

    public PasswordCipher(JsonMapper json) {
        this.json = json;
    }

    private static byte[] derive(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters p = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt)
                .withMemoryAsKB(memory).withIterations(iterations).withParallelism(parallelism).build();
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(p);
        byte[] key = new byte[32];
        g.generateBytes(password, key, 0, key.length);
        return key;
    }

    public String encrypt(String plain, char[] password) {
        try {
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(nonce);
            byte[] key = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var env = json.createObjectNode();
            env.put("formato", FORMAT);
            env.put("versao", VERSION);
            env.put("kdf", "argon2id");
            env.put("m", MEMORY_KIB);
            env.put("t", ITERATIONS);
            env.put("p", PARALLELISM);
            env.put("sal", Base64.getEncoder().encodeToString(salt));
            env.put("nonce", Base64.getEncoder().encodeToString(nonce));
            env.put("dados", Base64.getEncoder().encodeToString(data));
            return json.writeValueAsString(env);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar a configuração", e);
        }
    }

    public String decrypt(String envelope, char[] password) {
        JsonNode env;
        byte[] salt;
        byte[] nonce;
        byte[] data;
        try {
            env = json.readTree(envelope);
            if (!FORMAT.equals(env.path("formato").asText()) || env.path("versao").asInt() != VERSION
                    || !"argon2id".equals(env.path("kdf").asText())) {
                throw new InvalidFile("formato desconhecido");
            }
            salt = Base64.getDecoder().decode(env.path("sal").asText());
            nonce = Base64.getDecoder().decode(env.path("nonce").asText());
            data = Base64.getDecoder().decode(env.path("dados").asText());
        } catch (InvalidFile e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidFile(String.valueOf(e.getMessage()));
        }
        int m = env.path("m").asInt(MEMORY_KIB);
        int t = env.path("t").asInt(ITERATIONS);
        int p = env.path("p").asInt(PARALLELISM);
        if (m > 1_048_576 || t > 10 || p > 8 || m < 8 || t < 1 || p < 1 || nonce.length != 12) {
            throw new InvalidFile("parâmetros inválidos");
        }
        try {
            byte[] key = derive(password, salt, m, t, p);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPassword();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar a configuração", e);
        }
    }
}
