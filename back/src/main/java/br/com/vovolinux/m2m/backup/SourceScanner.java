package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.JobFailure;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Varredura prévia (8.1): percorre os diretórios de origem sem seguir links, conta itens e bytes e grava a lista no
 * NDJSON temporário. Itens especiais (FIFO, socket, dispositivo) nunca entram: viram avisos.
 */
@Component
public class SourceScanner {

    public record Summary(long items, long files, long directories, long symlinks, long bytes,
            List<String> specials) {
    }

    private final ManifestIO manifestIO;

    public SourceScanner(ManifestIO manifestIO) {
        this.manifestIO = manifestIO;
    }

    /**
     * @param roots caminhos de origem, na ordem dos índices dir-1, dir-2...
     */
    public Summary scan(FileGateway gw, List<String> roots, Path ndjson, CancellationToken token,
            java.util.function.Consumer<String> current) throws IOException {
        long files = 0;
        long dirs = 0;
        long links = 0;
        long bytes = 0;
        List<String> specials = new ArrayList<>();
        try (BufferedWriter out = manifestIO.openItemsWriter(ndjson)) {
            for (int i = 0; i < roots.size(); i++) {
                String root = roots.get(i);
                FileEntry r;
                try {
                    r = gw.lstat(root);
                } catch (NoSuchFileException e) {
                    throw new JobFailure("backup.diretorio-origem-ausente", Map.of("diretorio", root));
                }
                if (!r.isDirectory()) {
                    throw new JobFailure("backup.diretorio-origem-ausente", Map.of("diretorio", root));
                }
                manifestIO.writeItem(out, new Manifest.Item(i + 1, "", "DIR", 0, r.mtime().toEpochMilli(), r.mode(),
                        null, null));
                dirs++;
                // pilha explícita: pré-ordem, filhos em ordem alfabética
                java.util.ArrayDeque<String[]> stack = new java.util.ArrayDeque<>();
                stack.push(new String[] {root, ""});
                while (!stack.isEmpty()) {
                    token.throwIfCancelled();
                    String[] cur = stack.pop();
                    String dirPath = cur[0];
                    String rel = cur[1];
                    List<FileEntry> children = list(gw, dirPath);
                    children.sort(Comparator.comparing(FileEntry::name));
                    List<String[]> subdirs = new ArrayList<>();
                    for (FileEntry c : children) {
                        String childRel = rel.isEmpty() ? c.name() : rel + "/" + c.name();
                        current.accept(c.path());
                        switch (c.type()) {
                            case DIRECTORY -> {
                                manifestIO.writeItem(out, new Manifest.Item(i + 1, childRel, "DIR", 0,
                                        c.mtime().toEpochMilli(), c.mode(), null, null));
                                dirs++;
                                subdirs.add(new String[] {c.path(), childRel});
                            }
                            case FILE -> {
                                manifestIO.writeItem(out, new Manifest.Item(i + 1, childRel, "FILE", c.size(),
                                        c.mtime().toEpochMilli(), c.mode(), null, null));
                                files++;
                                bytes += c.size();
                            }
                            case SYMLINK -> {
                                manifestIO.writeItem(out, new Manifest.Item(i + 1, childRel, "SYMLINK", 0,
                                        c.mtime().toEpochMilli(), c.mode(), c.linkTarget(), null));
                                links++;
                            }
                            case SPECIAL -> specials.add(c.path());
                        }
                    }
                    // empilha ao contrário para visitar em ordem alfabética
                    for (int k = subdirs.size() - 1; k >= 0; k--) {
                        stack.push(subdirs.get(k));
                    }
                }
            }
        }
        return new Summary(files + dirs + links, files, dirs, links, bytes, specials);
    }

    private List<FileEntry> list(FileGateway gw, String dir) throws IOException {
        try {
            return new ArrayList<>(gw.list(dir));
        } catch (NoSuchFileException e) {
            throw new JobFailure("backup.erro-de-leitura", Map.of("caminho", dir, "detalhe", "diretório removido"));
        } catch (java.nio.file.AccessDeniedException e) {
            throw new JobFailure("backup.erro-de-leitura", Map.of("caminho", dir, "detalhe", "permissão negada"));
        }
    }

    static boolean isFile(FileEntry e) {
        return e.type() == FileType.FILE;
    }
}
