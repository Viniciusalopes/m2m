package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.job.CancellationToken;
import com.github.luben.zstd.ZstdInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validação de integridade (D6): descomprime o arquivo inteiro, calcula o SHA-256 de cada entrada e confere com o
 * manifesto embutido (última entrada), a contagem de itens e o SHA-256 do próprio arquivo.
 */
@Component
public class ArchiveValidator {

    /** Falha de validação com o item afetado. */
    public static class ValidationFailed extends IOException {
        private final String item;

        public ValidationFailed(String motivo, String item) {
            super(motivo + (item == null ? "" : " [" + item + "]"));
            this.item = item;
        }

        public String item() {
            return item;
        }
    }

    public record Result(Manifest.Header header, long items, long files, long bytes, long archiveBytes,
            String archiveSha256) {
    }

    private static final int CHUNK = 1 << 16;

    private final ManifestIO manifestIO;
    private final JsonMapper json;

    public ArchiveValidator(ManifestIO manifestIO, JsonMapper json) {
        this.manifestIO = manifestIO;
        this.json = json;
    }

    /**
     * @param archive bytes do {@code .tar.zst} em claro (já decifrados, se for o caso)
     * @param workDir diretório temporário para a lista calculada e o manifesto extraído
     */
    public Result validate(InputStream archive, Path workDir, CancellationToken token) throws IOException {
        Files.createDirectories(workDir);
        Path computed = Files.createTempFile(workDir, "computed-", ".ndjson");
        Path manifestFile = Files.createTempFile(workDir, "manifest-", ".json");
        HashingInputStream raw = new HashingInputStream(archive);
        long[] counts = new long[3]; // itens, arquivos, bytes
        boolean sawManifest = false;
        try {
            try (BufferedWriter out = Files.newBufferedWriter(computed, StandardCharsets.UTF_8);
                    TarArchiveInputStream tar = new TarArchiveInputStream(new ZstdInputStream(raw),
                            StandardCharsets.UTF_8.name())) {
                TarArchiveEntry e;
                byte[] buf = new byte[CHUNK];
                while ((e = tar.getNextEntry()) != null) {
                    token.throwIfCancelled();
                    if (sawManifest) {
                        throw new ValidationFailed("entrada depois do manifesto", e.getName());
                    }
                    String name = e.getName();
                    if (name.equals(Manifest.INTERNAL_ENTRY)) {
                        try (OutputStream mo = Files.newOutputStream(manifestFile)) {
                            tar.transferTo(mo);
                        }
                        sawManifest = true;
                        continue;
                    }
                    String type;
                    String link = null;
                    String sha = null;
                    long size = 0;
                    if (e.isDirectory()) {
                        type = "DIR";
                        name = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    } else if (e.isSymbolicLink()) {
                        type = "SYMLINK";
                        link = e.getLinkName();
                    } else if (e.isFile()) {
                        type = "FILE";
                        MessageDigest md = sha256();
                        int n;
                        while ((n = tar.read(buf)) > 0) {
                            md.update(buf, 0, n);
                            size += n;
                        }
                        sha = HexFormat.of().formatHex(md.digest());
                        if (size != e.getSize()) {
                            throw new ValidationFailed("tamanho divergente", name);
                        }
                        counts[1]++;
                        counts[2] += size;
                    } else {
                        throw new ValidationFailed("tipo de entrada inesperado", name);
                    }
                    counts[0]++;
                    // dir=0 e path=nome completo da entrada: a comparação usa o nome inteiro
                    manifestIO.writeItem(out, new Manifest.Item(0, name, type, size, e.getModTime().getTime(),
                            e.getMode() & 0777, link, sha));
                }
                // lê o que sobrou do arquivo (preenchimento do tar) para o hash cobrir todos os bytes
                raw.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
                if (e instanceof ValidationFailed) {
                    throw e;
                }
                throw new ValidationFailed("arquivo ilegível: " + e.getMessage(), null);
            }
            if (!sawManifest) {
                throw new ValidationFailed("manifesto ausente", Manifest.INTERNAL_ENTRY);
            }
            Manifest.Header header = compare(computed, manifestFile, counts[0]);
            return new Result(header, counts[0], counts[1], counts[2], raw.count(), raw.sha256());
        } finally {
            Files.deleteIfExists(computed);
            Files.deleteIfExists(manifestFile);
        }
    }

    private Manifest.Header compare(Path computed, Path manifestFile, long computedItems) throws IOException {
        long[] seen = {0};
        try (BufferedReader expected = Files.newBufferedReader(computed, StandardCharsets.UTF_8);
                InputStream mi = Files.newInputStream(manifestFile)) {
            Manifest.Header header = manifestIO.read(mi, item -> {
                try {
                    String line = expected.readLine();
                    if (line == null) {
                        throw new ValidationFailed("item do manifesto sem entrada no arquivo", item.entryName());
                    }
                    Manifest.Item actual = json.readValue(line, Manifest.Item.class);
                    check(item, actual);
                    seen[0]++;
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            if (expected.readLine() != null || seen[0] != computedItems) {
                throw new ValidationFailed("o arquivo tem mais entradas do que o manifesto", null);
            }
            return header;
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void check(Manifest.Item m, Manifest.Item a) throws ValidationFailed {
        String name = m.entryName();
        if (!name.equals(a.path())) {
            throw new ValidationFailed("entrada diferente do manifesto (" + a.path() + ")", name);
        }
        if (!m.type().equals(a.type())) {
            throw new ValidationFailed("tipo divergente", name);
        }
        if (m.type().equals("FILE")) {
            if (m.size() != a.size()) {
                throw new ValidationFailed("tamanho divergente", name);
            }
            if (m.sha256() == null || !m.sha256().equals(a.sha256())) {
                throw new ValidationFailed("SHA-256 divergente", name);
            }
        }
        if (m.type().equals("SYMLINK") && !java.util.Objects.equals(m.link(), a.link())) {
            throw new ValidationFailed("alvo do link divergente", name);
        }
        if (m.mode() != null && (m.mode() & 0777) != a.mode()) {
            throw new ValidationFailed("permissões divergentes", name);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
