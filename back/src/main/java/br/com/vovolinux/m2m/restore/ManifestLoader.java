package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.backup.ManifestIO;
import br.com.vovolinux.m2m.compare.ManifestTree;
import br.com.vovolinux.m2m.files.FileGateway;
import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

/** Carrega o manifesto de um backup: pelo manifesto lateral ou, na falta dele, pelo interno (lendo o arquivo). */
@Component
public class ManifestLoader {

    public record Loaded(Manifest.Header header, Map<Integer, ManifestTree> trees,
            Map<Integer, java.util.List<Manifest.Item>> topLevel) {
    }

    private final ManifestIO manifestIO;

    public ManifestLoader(ManifestIO manifestIO) {
        this.manifestIO = manifestIO;
    }

    public Loaded load(FileGateway storage, BackupArchive a) throws IOException {
        String path = ArchiveFiles.path(a);
        try (InputStream side = new ZstdInputStream(storage.openRead(path + ArchiveFiles.MANIFEST_SUFFIX, 0))) {
            return parse(side, a);
        } catch (NoSuchFileException e) {
            return loadInternal(storage, a);
        }
    }

    /** Fallback: percorre o tar até a última entrada (.m2m-manifest.json). */
    private Loaded loadInternal(FileGateway storage, BackupArchive a) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ZstdInputStream(storage.openRead(ArchiveFiles.path(a), 0)), StandardCharsets.UTF_8.name())) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.getName().equals(Manifest.INTERNAL_ENTRY)) {
                    return parse(tar, a);
                }
            }
        }
        throw new IOException("Manifesto interno ausente em " + a.fileName());
    }

    private Loaded parse(InputStream in, BackupArchive a) throws IOException {
        Map<Integer, ManifestTree> trees = new TreeMap<>();
        boolean full = a.fullFidelity();
        Manifest.Header header = manifestIO.read(in, item -> trees
                .computeIfAbsent(item.dir(), d -> new ManifestTree("backup:dir-" + d, full)).add(item));
        return new Loaded(header, trees, Map.of());
    }
}
