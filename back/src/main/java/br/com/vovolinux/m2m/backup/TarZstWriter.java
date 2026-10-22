package br.com.vovolinux.m2m.backup;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** Escritor {@code .tar.zst} em streaming (tar PAX + zstd nível 3), direto num {@link OutputStream} (D4). */
public final class TarZstWriter implements Closeable {

    public static final int ZSTD_LEVEL = 3;
    static final int DEFAULT_FILE_MODE = 0644;
    static final int DEFAULT_DIR_MODE = 0755;

    private final TarArchiveOutputStream tar;

    public TarZstWriter(OutputStream sink) throws IOException {
        ZstdOutputStream zstd = new ZstdOutputStream(sink, ZSTD_LEVEL);
        this.tar = new TarArchiveOutputStream(zstd, StandardCharsets.UTF_8.name());
        this.tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        this.tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        this.tar.setAddPaxHeadersForNonAsciiNames(true);
    }

    public void directory(String name, long mtimeMillis, Integer mode) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
        e.setModTime(mtimeMillis);
        e.setMode(mode == null ? DEFAULT_DIR_MODE : mode);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    public void symlink(String name, String target, long mtimeMillis, Integer mode) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
        e.setLinkName(target);
        e.setModTime(mtimeMillis);
        e.setMode(mode == null ? 0777 : mode);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    public void file(String name, long size, long mtimeMillis, Integer mode, Spool content) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(size);
        e.setModTime(mtimeMillis);
        e.setMode(mode == null ? DEFAULT_FILE_MODE : mode);
        tar.putArchiveEntry(e);
        content.writeTo(tar);
        tar.closeArchiveEntry();
    }

    public void bytes(String name, byte[] data, long mtimeMillis) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(data.length);
        e.setModTime(mtimeMillis);
        e.setMode(DEFAULT_FILE_MODE);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    /** Entrada cujo conteúdo é produzido por quem chama (ex.: o manifesto em streaming, com tamanho conhecido). */
    public void streamed(String name, long size, long mtimeMillis, java.io.InputStream in) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(size);
        e.setModTime(mtimeMillis);
        e.setMode(DEFAULT_FILE_MODE);
        tar.putArchiveEntry(e);
        in.transferTo(tar);
        tar.closeArchiveEntry();
    }

    @Override
    public void close() throws IOException {
        tar.finish();
        tar.close();
    }
}
