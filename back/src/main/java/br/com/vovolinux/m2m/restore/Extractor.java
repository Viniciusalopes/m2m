package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.conflict.ConflictPolicy;
import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.job.JobContext;
import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

/**
 * Extrai um {@code .tar.zst} num destino qualquer (D8): cada arquivo é gravado como {@code .<nome>.m2m-tmp} no mesmo
 * diretório e renomeado sobre o original; modo e data seguem as capacidades do destino; as datas dos diretórios são
 * aplicadas no fim. Conflitos de tipo removem o item antigo (restauração em espelho) ou são pulados (seletiva).
 */
@Component
public class Extractor {

    /** O que fazer com um item que já existe: decisão da política e, em RENAME, o novo caminho. */
    public record Resolution(ConflictPolicy.Decision decision, String path) {
    }

    @FunctionalInterface
    public interface ConflictResolver {
        Resolution resolve(String entryName, String destPath, ConflictPolicy.Meta incoming, FileEntry existing)
                throws IOException;
    }

    public record Options(
            /** nome da entrada do tar → caminho no destino, ou {@code null} para ignorar a entrada */
            Function<String, String> destinationOf,
            /** {@code null} = sempre sobrescreve (restauração em espelho) */
            ConflictResolver conflicts,
            /** conflito de tipo (arquivo × diretório): remove o item antigo? Falso na restauração seletiva. */
            boolean replaceTypeConflicts) {
    }

    public static final class Counts {
        public long created;
        public long overwritten;
        public long skipped;
        public long linksSkipped;
        public long bytes;
    }

    private record DirMeta(String path, Integer mode, long mtimeMillis) {
    }

    private static final int CHUNK = 1 << 16;
    static final String TMP_SUFFIX = ".m2m-tmp";

    public Counts extract(JobContext ctx, InputStream archive, FileGateway dst, Options opts) throws IOException {
        Counts counts = new Counts();
        Capabilities caps = dst.capabilities();
        List<DirMeta> dirs = new ArrayList<>();
        Set<String> knownDirs = new HashSet<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ZstdInputStream(archive),
                StandardCharsets.UTF_8.name())) {
            TarArchiveEntry e;
            byte[] buf = new byte[CHUNK];
            while ((e = tar.getNextEntry()) != null) {
                ctx.token().throwIfCancelled();
                String name = e.isDirectory() && e.getName().endsWith("/")
                        ? e.getName().substring(0, e.getName().length() - 1) : e.getName();
                if (name.equals(Manifest.INTERNAL_ENTRY)) {
                    continue;
                }
                String dest = opts.destinationOf().apply(name);
                if (dest == null) {
                    continue;
                }
                ctx.progress().current(dest);
                long mtime = e.getModTime().getTime();
                Integer mode = e.getMode() & 0777;
                if (e.isDirectory()) {
                    extractDir(dst, dest, mode, mtime, opts, counts, dirs, knownDirs, ctx);
                } else if (e.isSymbolicLink()) {
                    extractLink(dst, dest, e.getLinkName(), caps, counts, opts, ctx, knownDirs);
                } else if (e.isFile()) {
                    extractFile(ctx, tar, dst, name, dest, e.getSize(), mode, mtime, caps, opts, counts, knownDirs,
                            buf);
                }
                ctx.progress().itemDone();
            }
        }
        // diretórios por último, dos mais profundos aos mais rasos (gravar dentro deles muda a data)
        for (int i = dirs.size() - 1; i >= 0; i--) {
            ctx.token().throwIfCancelled();
            DirMeta d = dirs.get(i);
            if (caps.preservesMode() && d.mode() != null) {
                dst.setMode(d.path(), d.mode());
            }
            if (caps.setsMtime()) {
                dst.setMtime(d.path(), Instant.ofEpochMilli(d.mtimeMillis()));
            }
        }
        return counts;
    }

    private void extractDir(FileGateway dst, String dest, Integer mode, long mtime, Options opts, Counts counts,
            List<DirMeta> dirs, Set<String> knownDirs, JobContext ctx) throws IOException {
        Optional<FileEntry> existing = dst.lstatIfExists(dest);
        if (existing.isPresent() && !existing.get().isDirectory()) {
            if (!opts.replaceTypeConflicts()) {
                ctx.message("WARN", "restauracao.conflito-de-tipo-ignorado", Map.of("caminho", dest));
                counts.skipped++;
                return;
            }
            FsOps.deleteTree(dst, dest);
            existing = Optional.empty();
        }
        if (existing.isEmpty()) {
            dst.mkdirs(dest);
            counts.created++;
        }
        knownDirs.add(dest);
        dirs.add(new DirMeta(dest, mode, mtime));
    }

    private void ensureParent(FileGateway dst, String dest, Set<String> knownDirs) throws IOException {
        String parent = FsOps.parentOf(dest);
        if (knownDirs.add(parent)) {
            dst.mkdirs(parent);
        }
    }

    private void extractLink(FileGateway dst, String dest, String target, Capabilities caps, Counts counts,
            Options opts, JobContext ctx, Set<String> knownDirs) throws IOException {
        if (!caps.preservesLinks()) {
            counts.linksSkipped++;
            ctx.message("WARN", "restauracao.link-nao-restaurado", Map.of("caminho", dest, "alvo", target));
            return;
        }
        ensureParent(dst, dest, knownDirs);
        Optional<FileEntry> existing = dst.lstatIfExists(dest);
        if (existing.isPresent()) {
            if (existing.get().isDirectory() && !opts.replaceTypeConflicts()) {
                counts.skipped++;
                ctx.message("WARN", "restauracao.conflito-de-tipo-ignorado", Map.of("caminho", dest));
                return;
            }
            FsOps.deleteTree(dst, dest);
            counts.overwritten++;
        } else {
            counts.created++;
        }
        dst.symlink(dest, target);
    }

    private void extractFile(JobContext ctx, TarArchiveInputStream tar, FileGateway dst, String entryName,
            String requestedDest, long size, Integer mode, long mtime, Capabilities caps, Options opts, Counts counts,
            Set<String> knownDirs, byte[] buf) throws IOException {
        String dest = requestedDest;
        ensureParent(dst, dest, knownDirs);
        Optional<FileEntry> existing = dst.lstatIfExists(dest);
        boolean overwrite = false;
        if (existing.isPresent()) {
            FileEntry ex = existing.get();
            if (ex.isDirectory() && !opts.replaceTypeConflicts()) {
                counts.skipped++;
                ctx.message("WARN", "restauracao.conflito-de-tipo-ignorado", Map.of("caminho", dest));
                return; // o tar avança sozinho para a próxima entrada
            }
            if (opts.conflicts() != null && !ex.isDirectory()) {
                Resolution r = opts.conflicts().resolve(entryName, dest, new ConflictPolicy.Meta(size, mtime), ex);
                if (r.decision() == ConflictPolicy.Decision.SKIP) {
                    counts.skipped++;
                    return;
                }
                if (r.decision() == ConflictPolicy.Decision.RENAME) {
                    dest = r.path();
                    existing = dst.lstatIfExists(dest); // nome novo, normalmente livre
                }
            }
            overwrite = existing.isPresent();
        }
        String parent = FsOps.parentOf(dest);
        String tmp = parent + (parent.equals("/") ? "" : "/") + "." + FsOps.nameOf(dest) + TMP_SUFFIX;
        boolean done = false;
        try {
            try (OutputStream out = dst.openWrite(tmp, 0)) {
                int n;
                while ((n = tar.read(buf)) > 0) {
                    ctx.token().throwIfCancelled();
                    out.write(buf, 0, n);
                    ctx.progress().addBytes(n);
                    counts.bytes += n;
                }
            }
            if (caps.preservesMode() && mode != null) {
                dst.setMode(tmp, mode);
            }
            if (caps.setsMtime()) {
                dst.setMtime(tmp, Instant.ofEpochMilli(mtime));
            }
            if (existing.isPresent() && existing.get().isDirectory()) {
                FsOps.deleteTree(dst, dest);
            }
            dst.rename(tmp, dest);
            done = true;
        } finally {
            if (!done) {
                try {
                    dst.delete(tmp);
                } catch (NoSuchFileException ignorado) {
                    // nunca chegou a existir
                } catch (IOException ignorado) {
                    // a conexão pode ter sido fechada à força
                }
            }
        }
        if (overwrite) {
            counts.overwritten++;
        } else {
            counts.created++;
        }
    }
}
