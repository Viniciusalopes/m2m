package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.JobFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongConsumer;
import org.springframework.stereotype.Component;

/**
 * Lê um arquivo da origem com detecção de alteração (D5): {@code lstat} antes e depois; se o tamanho ou a data
 * mudaram, ou os bytes lidos não batem, descarta e lê de novo (1 nova tentativa); depois disso fica com a última
 * leitura e avisa.
 */
@Component
public class SourceFileReader {

    public static final int ATTEMPTS = 2;
    private static final int CHUNK = 1 << 16;

    /** Conteúdo lido. O chamador fecha o {@link Spool}. */
    public record Result(Spool content, long size, String sha256, long mtimeMillis, Integer mode, boolean changed,
            boolean skipped) {

        static Result missing() {
            return new Result(null, 0, null, 0, null, false, true);
        }
    }

    public Result read(FileGateway gw, String path, Path tmpDir, int spoolLimit, CancellationToken token,
            LongConsumer onBytes) throws IOException {
        Result last = null;
        long countedBytes = 0;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            token.throwIfCancelled();
            FileEntry before;
            try {
                before = gw.lstat(path);
            } catch (NoSuchFileException e) {
                if (last != null) {
                    last.content().close();
                }
                return Result.missing(); // sumiu entre a varredura e a leitura
            } catch (AccessDeniedException e) {
                throw denied(path, e);
            }
            if (before.type() != FileType.FILE) {
                if (last != null) {
                    last.content().close();
                }
                return Result.missing(); // virou link/diretório/especial: não é lido
            }
            if (last != null) {
                last.content().close();
                onBytes.accept(-countedBytes); // a leitura anterior foi descartada
                countedBytes = 0;
            }
            Spool spool = new Spool(tmpDir, spoolLimit);
            MessageDigest md = sha256();
            long read = 0;
            try (InputStream in = gw.openRead(path, 0)) {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = in.read(buf)) > 0) {
                    token.throwIfCancelled();
                    spool.write(buf, 0, n);
                    md.update(buf, 0, n);
                    read += n;
                    onBytes.accept(n);
                    countedBytes += n;
                }
                spool.finish();
            } catch (AccessDeniedException e) {
                spool.close();
                throw denied(path, e);
            } catch (IOException | RuntimeException e) {
                spool.close();
                if (e instanceof java.util.concurrent.CancellationException) {
                    throw e;
                }
                if (token.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("interrompido");
                }
                if (e instanceof IOException io && io.getMessage() != null
                        && io.getMessage().toLowerCase().contains("permission")) {
                    throw denied(path, io);
                }
                throw e;
            }
            FileEntry after;
            try {
                after = gw.lstat(path);
            } catch (NoSuchFileException e) {
                spool.close();
                return Result.missing();
            }
            boolean stable = before.size() == after.size() && before.mtime().equals(after.mtime())
                    && read == before.size();
            last = new Result(spool, read, HexFormat.of().formatHex(md.digest()), after.mtime().toEpochMilli(),
                    after.mode(), !stable, false);
            if (stable) {
                return last;
            }
        }
        return last; // continuou mudando: usa a última leitura, marcada como alterada
    }

    private static JobFailure denied(String path, IOException e) {
        return new JobFailure("backup.erro-de-leitura", Map.of("caminho", path, "detalhe", "permissão negada"));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
