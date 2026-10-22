package br.com.vovolinux.m2m.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP (RFC 6238): HMAC-SHA1, 6 dígitos, passos de 30 s, tolerância de ±1 passo. Só {@code javax.crypto} (D31). */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int WINDOW = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** Segredo de 160 bits em Base32, o formato que os aplicativos autenticadores leem. */
    public static String newSecret(SecureRandom random) {
        byte[] raw = new byte[20];
        random.nextBytes(raw);
        return encodeBase32(raw);
    }

    public static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static byte[] decodeBase32(String text) {
        String s = text.replace(" ", "").replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Base32 inválido");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    /** O código de 6 dígitos do passo {@code step} (RFC 4226, truncamento dinâmico). */
    public static int code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0F;
            int binary = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16) | ((h[offset + 2] & 0xFF) << 8)
                    | (h[offset + 3] & 0xFF);
            return binary % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long stepOf(long epochMillis) {
        return epochMillis / 1000 / PERIOD_SECONDS;
    }

    public static String format(int code) {
        return String.format("%0" + DIGITS + "d", code);
    }

    /**
     * Confere o código nos passos vizinhos ao atual.
     *
     * @param lastStep último passo já aceito (um código só vale uma vez), ou {@code -1}
     * @return o passo que casou, ou vazio
     */
    public static OptionalLong verify(byte[] key, String code, long nowMillis, long lastStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long now = stepOf(nowMillis);
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            if (s > lastStep && format(code(key, s)).equals(code)) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    /** URI {@code otpauth://} que o front transforma em QR code. */
    public static String uri(String issuer, String account, String secret) {
        String label = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + ":"
                + URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8) + "&algorithm=SHA1&digits=" + DIGITS + "&period="
                + PERIOD_SECONDS;
    }
}
