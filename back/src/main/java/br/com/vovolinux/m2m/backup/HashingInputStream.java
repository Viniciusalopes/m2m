package br.com.vovolinux.m2m.backup;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Conta os bytes e calcula o SHA-256 de tudo que é lido do arquivo (cobre o arquivo inteiro). */
public final class HashingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private long count;

    public HashingInputStream(InputStream in) {
        super(in);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            digest.update((byte) b);
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            digest.update(b, off, n);
            count += n;
        }
        return n;
    }

    public long count() {
        return count;
    }

    public String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
