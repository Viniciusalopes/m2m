package br.com.vovolinux.m2m.security;

import br.com.vovolinux.m2m.crypto.CryptoService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Segundo fator (D31): segredo cifrado pela chave mestra, 10 códigos de recuperação guardados como hash BCrypt. */
@Service
public class TotpService {

    public static final String ISSUER = "m2m";
    public static final int RECOVERY_CODES = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final PasswordEncoder encoder;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    public TotpService(JdbcClient jdbc, CryptoService crypto, PasswordEncoder encoder, JsonMapper json) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.encoder = encoder;
        this.json = json;
    }

    private record Row(byte[] secretEnc, boolean enabled, String recovery, Long lastStep) {
    }

    private Optional<Row> row(long userId) {
        return jdbc.sql("select secret_enc, enabled, recovery_codes::text, last_step from user_totp where user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new Row(rs.getBytes(1), rs.getBoolean(2), rs.getString(3), (Long) rs.getObject(4)))
                .optional();
    }

    public boolean isEnabled(long userId) {
        return row(userId).map(Row::enabled).orElse(false);
    }

    /** Há uma configuração começada e ainda não confirmada. */
    public boolean setupPending(long userId) {
        return row(userId).map(r -> !r.enabled()).orElse(false);
    }

    public int recoveryCodesLeft(long userId) {
        return row(userId).filter(Row::enabled).map(r -> json.readValue(r.recovery(), new TypeReference<List<String>>() {
        }).size()).orElse(0);
    }

    // ---- ativação ----

    public record Setup(String secret, String otpauthUri) {
    }

    /** Gera (ou regera, enquanto não confirmado) o segredo; o front mostra o QR code. */
    @Transactional
    public Optional<Setup> begin(long userId, String account) {
        Optional<Row> existing = row(userId);
        if (existing.isPresent() && existing.get().enabled()) {
            return Optional.empty();
        }
        String secret = Totp.newSecret(random);
        jdbc.sql("""
                insert into user_totp (user_id, secret_enc, enabled, recovery_codes, last_step)
                values (:u, :s, false, '[]'::jsonb, null)
                on conflict (user_id) do update set secret_enc = excluded.secret_enc, enabled = false,
                       recovery_codes = '[]'::jsonb, last_step = null, created_at = now()""")
                .param("u", userId).param("s", crypto.encrypt(secret)).update();
        return Optional.of(new Setup(secret, Totp.uri(ISSUER, account, secret)));
    }

    /**
     * Confirma o primeiro código e ativa o segundo fator.
     *
     * @return os códigos de recuperação em claro (só agora), ou vazio se o código não confere
     */
    @Transactional
    public Optional<List<String>> enable(long userId, String code) {
        Row r = row(userId).orElse(null);
        if (r == null || r.enabled()) {
            return Optional.empty();
        }
        byte[] key = Totp.decodeBase32(crypto.decryptString(r.secretEnc()));
        OptionalLong step = Totp.verify(key, normalizeCode(code), System.currentTimeMillis(), -1);
        if (step.isEmpty()) {
            return Optional.empty();
        }
        List<String> plain = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = newRecoveryCode();
            plain.add(c.substring(0, 5) + "-" + c.substring(5));
            hashes.add(encoder.encode(c));
        }
        jdbc.sql("update user_totp set enabled = true, recovery_codes = cast(:r as jsonb), last_step = :s where user_id = :u")
                .param("r", json.writeValueAsString(hashes)).param("s", step.getAsLong()).param("u", userId).update();
        return Optional.of(plain);
    }

    private String newRecoveryCode() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    @Transactional
    public void disable(long userId) {
        jdbc.sql("delete from user_totp where user_id = :u").param("u", userId).update();
    }

    // ---- verificação no login ----

    private static String normalizeCode(String code) {
        return code == null ? "" : code.replace(" ", "").replace("-", "").trim();
    }

    /** Confere um código TOTP (uma só vez por passo) ou, na falta dele, um código de recuperação (uso único). */
    @Transactional
    public boolean verify(long userId, String raw) {
        Row r = row(userId).filter(Row::enabled).orElse(null);
        if (r == null) {
            return false;
        }
        String code = normalizeCode(raw);
        if (code.matches("\\d{" + Totp.DIGITS + "}")) {
            byte[] key = Totp.decodeBase32(crypto.decryptString(r.secretEnc()));
            OptionalLong step = Totp.verify(key, code, System.currentTimeMillis(), r.lastStep() == null ? -1 : r.lastStep());
            if (step.isPresent()) {
                // só um login por passo: grava o passo condicionalmente para fechar corrida entre duas requisições
                return jdbc.sql("update user_totp set last_step = :s where user_id = :u and (last_step is null or last_step < :s)")
                        .param("s", step.getAsLong()).param("u", userId).update() > 0;
            }
            return false;
        }
        String upper = code.toUpperCase();
        List<String> hashes = new ArrayList<>(json.readValue(r.recovery(), new TypeReference<List<String>>() {
        }));
        for (String h : hashes) {
            if (encoder.matches(upper, h)) {
                hashes.remove(h);
                jdbc.sql("update user_totp set recovery_codes = cast(:r as jsonb) where user_id = :u")
                        .param("r", json.writeValueAsString(hashes)).param("u", userId).update();
                return true;
            }
        }
        return false;
    }
}
