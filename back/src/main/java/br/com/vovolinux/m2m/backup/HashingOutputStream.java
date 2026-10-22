package br.com.vovolinux.m2m.backup;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Conta e calcula o SHA-256 de tudo que passa para o destino (o arquivo final do backup). */
public final class HashingOutputStream extends FilterOutputStream {

    private final MessageDigest digest;
    private long count;

    public HashingOutputStream(OutputStream out) {
        super(out);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        digest.update((byte) b);
        count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        digest.update(b, off, len);
        count += len;
    }

    public long count() {
        return count;
    }

    /** Hex do SHA-256; vale depois de fechar o fluxo. */
    public String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
