package br.com.vovolinux.m2m.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Armazena o conteúdo de um arquivo lido: até {@code limit} bytes em memória, acima disso num temporário em disco
 * (o tar exige o tamanho no cabeçalho antes do conteúdo — D5).
 */
public final class Spool implements AutoCloseable {

    public static final int DEFAULT_LIMIT = 64 * 1024 * 1024;

    private final int limit;
    private final Path tmpDir;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private Path file;
    private OutputStream fileOut;
    private long size;

    public Spool(Path tmpDir, int limit) {
        this.tmpDir = tmpDir;
        this.limit = limit;
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        if (fileOut == null && memory.size() + len > limit) {
            Files.createDirectories(tmpDir);
            file = Files.createTempFile(tmpDir, "spool-", ".bin");
            fileOut = Files.newOutputStream(file);
            memory.writeTo(fileOut);
            memory = null;
        }
        if (fileOut != null) {
            fileOut.write(buf, off, len);
        } else {
            memory.write(buf, off, len);
        }
        size += len;
    }

    public long size() {
        return size;
    }

    /** Encerra a escrita; depois disso só {@link #writeTo} e {@link #close}. */
    public void finish() throws IOException {
        if (fileOut != null) {
            fileOut.close();
            fileOut = null;
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        if (file != null) {
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(out);
            }
        } else {
            memory.writeTo(out);
        }
    }

    @Override
    public void close() throws IOException {
        if (fileOut != null) {
            fileOut.close();
        }
        if (file != null) {
            Files.deleteIfExists(file);
            file = null;
        }
        memory = null;
    }
}
