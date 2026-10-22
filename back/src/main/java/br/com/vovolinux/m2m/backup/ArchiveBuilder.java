package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.crypto.ArchiveCrypto;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobProgress;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Núcleo de gravação de um arquivo de backup (D4–D6), compartilhado pelos backups comuns e pelos de segurança
 * (PRE_RESTORE, PRE_SYNC): grava o {@code .tar.zst} em {@code .partial}, valida por releitura, renomeia e grava o
 * manifesto lateral. O chamador cuida do registro no banco e da retenção.
 */
@Component
public class ArchiveBuilder {

    /** Estado de uma gravação, para a limpeza em qualquer desfecho. */
    public static final class Session {
        public final Path tmpDir;
        /** Caminho do arquivo parcial enquanto ele existir no armazenamento. */
        public String partialPath;
        public String finalName;
        public String archiveSha;
        /** Raízes de origem (índice dir-N → caminho), na ordem do manifesto. */
        public List<String> roots = List.of();
        public final List<Map<String, Object>> warnings = new ArrayList<>();
        /** Chave de criptografia do perfil; {@code null} grava o arquivo em claro. */
        public ArchiveCrypto.Key key;

        public Session(Path tmpDir) {
            this.tmpDir = tmpDir;
        }
    }

    public record Built(String finalName, ArchiveValidator.Result validation) {
    }

    private final SourceFileReader reader;
    private final ManifestIO manifestIO;
    private final ArchiveValidator validator;
    private final ArchiveCrypto crypto;
    private final int spoolLimit;

    public ArchiveBuilder(SourceFileReader reader, ManifestIO manifestIO, ArchiveValidator validator,
            ArchiveCrypto crypto, @Value("${m2m.backup.spool-limit:" + Spool.DEFAULT_LIMIT + "}") int spoolLimit) {
        this.reader = reader;
        this.manifestIO = manifestIO;
        this.validator = validator;
        this.crypto = crypto;
        this.spoolLimit = spoolLimit;
    }

    /** Escolhe um nome livre no armazenamento: duas execuções no mesmo segundo esperariam o próximo. */
    public String uniqueName(FileGateway dst, String dir, java.util.function.Function<ZonedDateTime, String> namer,
            ZonedDateTime started) throws IOException {
        ZonedDateTime t = started;
        String name = namer.apply(t);
        for (int i = 0; i < 5; i++) {
            boolean exists = dst.lstatIfExists(dir + "/" + name).isPresent()
                    || dst.lstatIfExists(dir + "/" + BackupNaming.partial(name)).isPresent();
            if (!exists) {
                return name;
            }
            t = t.plusSeconds(1);
            name = namer.apply(t);
        }
        return name;
    }

    /**
     * Grava, valida e finaliza o arquivo a partir da lista de itens em {@code scanFile} (formato NDJSON do
     * {@link SourceScanner}). Os avisos vão para {@code session.warnings}.
     */
    public Built build(JobContext ctx, Session session, FileGateway src, FileGateway dst, String storageDir,
            String finalName, Path scanFile, Manifest.Header header) throws IOException {
        JobProgress progress = ctx.progress();
        session.finalName = finalName;
        String partial = storageDir + "/" + BackupNaming.partial(finalName);
        session.partialPath = partial;
        Path itemsFile = session.tmpDir.resolve("items.ndjson");

        progress.step("BACKING_UP");
        long readBytes = writeArchive(ctx, session, src, dst, partial, scanFile, itemsFile, header);

        progress.step("VALIDATING");
        progress.resetCounters();
        progress.totals(readBytes, 0);
        ArchiveValidator.Result validation;
        try (InputStream raw = new ProgressInputStream(dst.openRead(partial, 0), progress);
                InputStream in = session.key == null ? raw : crypto.decrypt(session.key, raw)) {
            validation = validator.validate(in, session.tmpDir, ctx.token());
        } catch (ArchiveValidator.ValidationFailed e) {
            throw new JobFailure("backup.validacao-falhou", Map.of("motivo", String.valueOf(e.getMessage())));
        }
        if (!validation.archiveSha256().equals(session.archiveSha)) {
            throw new JobFailure("backup.validacao-falhou", Map.of("motivo", "SHA-256 do arquivo divergente"));
        }

        progress.step("FINALIZING");
        String finalPath = storageDir + "/" + finalName;
        dst.rename(partial, finalPath);
        session.partialPath = null;
        writeSideManifest(dst, finalPath, itemsFile, header, session.key);
        return new Built(finalName, validation);
    }

    private long writeArchive(JobContext ctx, Session session, FileGateway src, FileGateway dst, String partial,
            Path scanFile, Path itemsFile, Manifest.Header header) throws IOException {
        JobProgress progress = ctx.progress();
        // o SHA-256 é do .tar.zst em claro; a cifragem fica por baixo, entre o hash e o armazenamento
        OutputStream file = dst.openWrite(partial, 0);
        HashingOutputStream sink = new HashingOutputStream(session.key == null ? file
                : crypto.encrypt(session.key, file));
        long[] total = {0};
        boolean ok = false;
        try (BufferedWriter items = manifestIO.openItemsWriter(itemsFile)) {
            try (TarZstWriter tar = new TarZstWriter(sink)) {
                manifestIO.forEachItemNdjson(scanFile, item -> {
                    try {
                        ctx.token().throwIfCancelled();
                        Manifest.Item written = writeItem(ctx, session, src, tar, item, progress);
                        if (written != null) {
                            manifestIO.writeItem(items, written);
                            total[0] += written.size();
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                items.flush();
                // manifesto como última entrada (8.5): primeiro num temporário, para conhecer o tamanho
                Path manifestTmp = session.tmpDir.resolve("manifest.json");
                try (OutputStream mo = Files.newOutputStream(manifestTmp)) {
                    manifestIO.writeManifest(mo, header, itemsFile);
                }
                try (InputStream mi = Files.newInputStream(manifestTmp)) {
                    tar.streamed(Manifest.INTERNAL_ENTRY, Files.size(manifestTmp), System.currentTimeMillis(), mi);
                }
            }
            ok = true;
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            if (!ok) {
                try {
                    sink.close();
                } catch (IOException ignorado) {
                    // a conexão pode ter sido fechada à força (cancelamento)
                }
            }
        }
        session.archiveSha = sink.sha256();
        return total[0];
    }

    /** Escreve um item no tar e devolve o item como entrará no manifesto (com SHA-256), ou {@code null} se pulado. */
    private Manifest.Item writeItem(JobContext ctx, Session session, FileGateway src, TarZstWriter tar,
            Manifest.Item scanned, JobProgress progress) throws IOException {
        String root = session.roots.get(scanned.dir() - 1);
        String srcPath = scanned.path().isEmpty() ? root : root + "/" + scanned.path();
        progress.current(srcPath);
        switch (scanned.type()) {
            case "DIR" -> {
                tar.directory(scanned.entryName(), scanned.mtime(), scanned.mode());
                progress.itemDone();
                return scanned;
            }
            case "SYMLINK" -> {
                tar.symlink(scanned.entryName(), scanned.link(), scanned.mtime(), scanned.mode());
                progress.itemDone();
                return scanned;
            }
            default -> {
                SourceFileReader.Result r = reader.read(src, srcPath, session.tmpDir, spoolLimit, ctx.token(),
                        progress::addBytes);
                if (r.skipped()) {
                    warn(ctx, session, "backup.arquivo-removido-durante-a-leitura", Map.of("caminho", srcPath));
                    progress.itemDone();
                    return null;
                }
                try (Spool content = r.content()) {
                    if (r.changed()) {
                        warn(ctx, session, "backup.arquivo-alterado-durante-a-leitura", Map.of("caminho", srcPath));
                    }
                    Integer mode = r.mode() != null ? r.mode() : scanned.mode();
                    tar.file(scanned.entryName(), r.size(), r.mtimeMillis(), mode, content);
                    progress.itemDone();
                    return new Manifest.Item(scanned.dir(), scanned.path(), "FILE", r.size(), r.mtimeMillis(), mode,
                            null, r.sha256());
                }
            }
        }
    }

    public void warn(JobContext ctx, Session session, String code, Map<String, Object> params) {
        ctx.message("WARN", code, params);
        Map<String, Object> w = new HashMap<>(params);
        w.put("codigo", code);
        session.warnings.add(w);
    }

    private void writeSideManifest(FileGateway dst, String finalPath, Path itemsFile, Manifest.Header header,
            ArchiveCrypto.Key key) throws IOException {
        String partial = finalPath + ArchiveFiles.MANIFEST_SUFFIX + ".partial";
        OutputStream raw = dst.openWrite(partial, 0);
        try (OutputStream out = key == null ? raw : crypto.encrypt(key, raw);
                com.github.luben.zstd.ZstdOutputStream z = new com.github.luben.zstd.ZstdOutputStream(out,
                        TarZstWriter.ZSTD_LEVEL)) {
            manifestIO.writeManifest(z, header, itemsFile);
        }
        dst.rename(partial, finalPath + ArchiveFiles.MANIFEST_SUFFIX);
    }

    /** Fluxo que atualiza a contagem de bytes lidos (progresso da validação). */
    public static final class ProgressInputStream extends java.io.FilterInputStream {
        private final JobProgress progress;

        public ProgressInputStream(InputStream in, JobProgress progress) {
            super(in);
            this.progress = progress;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                progress.addBytes(n);
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                progress.addBytes(1);
            }
            return b;
        }
    }
}
