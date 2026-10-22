package br.com.vovolinux.m2m.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.JobFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Detecção de alteração durante a leitura (D5), com um gateway que muda o arquivo "por baixo". */
class SourceFileReaderTest {

    /** Gateway de mentira: cada leitura devolve o próximo conteúdo programado; lstat acompanha. */
    static class Fake implements FileGateway {
        final ArrayDeque<byte[]> contents = new ArrayDeque<>();
        byte[] current = new byte[0];
        long mtimeSeconds = 100;
        boolean denied;
        boolean vanish;
        int reads;

        @Override
        public Capabilities capabilities() {
            return Capabilities.full();
        }

        @Override
        public List<FileEntry> list(String dir) {
            return List.of();
        }

        @Override
        public FileEntry lstat(String path) throws IOException {
            if (vanish) {
                throw new NoSuchFileException(path);
            }
            return new FileEntry("f", path, FileType.FILE, current.length, Instant.ofEpochSecond(mtimeSeconds), 0644, null);
        }

        @Override
        public InputStream openRead(String path, long offset) throws IOException {
            if (denied) {
                throw new AccessDeniedException(path);
            }
            reads++;
            current = contents.isEmpty() ? current : contents.poll();
            return new ByteArrayInputStream(current);
        }

        @Override public OutputStream openWrite(String path, long offset) { throw new UnsupportedOperationException(); }
        @Override public void mkdirs(String dir) { }
        @Override public void symlink(String path, String target) { }
        @Override public void setMode(String path, int mode) { }
        @Override public void setMtime(String path, Instant mtime) { }
        @Override public void rename(String from, String to) { }
        @Override public void delete(String path) { }
        @Override public OptionalLong freeSpace(String path) { return OptionalLong.empty(); }
        @Override public Optional<String> remoteSha256(String path) { return Optional.empty(); }
        @Override public void close() { }
    }

    @TempDir
    Path tmp;

    private final SourceFileReader reader = new SourceFileReader();

    private SourceFileReader.Result read(Fake gw) throws IOException {
        return reader.read(gw, "/x/arquivo", tmp, 1024, new CancellationToken(), n -> {
        });
    }

    @Test
    void arquivoEstavelEhLidoUmaVezSemAviso() throws Exception {
        Fake gw = new Fake();
        gw.current = "estavel".getBytes();
        var r = read(gw);
        assertThat(r.changed()).isFalse();
        assertThat(r.size()).isEqualTo(7);
        assertThat(gw.reads).isEqualTo(1);
        r.content().close();
    }

    /** O arquivo muda de tamanho entre o lstat anterior e o posterior da 1ª leitura; a 2ª é estável. */
    @Test
    void mudancaNaPrimeiraLeituraRefazATentativaESemAviso() throws Exception {
        AtomicLong lstats = new AtomicLong();
        Fake gw = new Fake() {
            @Override
            public FileEntry lstat(String path) throws IOException {
                // lstats 1 e 2 (antes/depois da 1ª leitura) divergem em tamanho; depois estabiliza
                long n = lstats.incrementAndGet();
                FileEntry e = super.lstat(path);
                if (n == 2) {
                    return new FileEntry(e.name(), e.path(), e.type(), e.size() + 5, e.mtime(), e.mode(), null);
                }
                return e;
            }
        };
        gw.current = "abcdef".getBytes();
        var r = read(gw);
        assertThat(r.changed()).isFalse();
        assertThat(gw.reads).isEqualTo(2);
        r.content().close();
    }

    @Test
    void continuaMudandoUsaAUltimaLeituraEAvisa() throws Exception {
        Fake gw = new Fake() {
            @Override
            public FileEntry lstat(String path) throws IOException {
                mtimeSeconds++; // a data muda a cada consulta: nunca estabiliza
                return super.lstat(path);
            }
        };
        gw.contents.add("primeira".getBytes());
        gw.contents.add("segunda leitura maior".getBytes());
        var r = read(gw);
        assertThat(r.changed()).isTrue();
        assertThat(gw.reads).isEqualTo(2);
        assertThat(r.size()).isEqualTo("segunda leitura maior".length());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        r.content().writeTo(out);
        assertThat(out.toString()).isEqualTo("segunda leitura maior");
        r.content().close();
    }

    @Test
    void permissaoNegadaFalhaComOCaminho() {
        Fake gw = new Fake();
        gw.denied = true;
        assertThatThrownBy(() -> read(gw)).isInstanceOf(JobFailure.class)
                .satisfies(e -> assertThat(((JobFailure) e).parametros()).containsEntry("caminho", "/x/arquivo"));
    }

    @Test
    void arquivoQueSumiuEntreAVarreduraEALeituraEhPulado() throws Exception {
        Fake gw = new Fake();
        gw.vanish = true;
        assertThat(read(gw).skipped()).isTrue();
    }

    @Test
    void arquivoGrandeVaiParaTemporarioEmDisco() throws Exception {
        Fake gw = new Fake();
        gw.current = new byte[5000];
        var r = reader.read(gw, "/x/grande", tmp, 1024, new CancellationToken(), n -> {
        });
        assertThat(r.size()).isEqualTo(5000);
        try (var s = java.nio.file.Files.list(tmp)) {
            assertThat(s.count()).isEqualTo(1); // temporário spool-*.bin
        }
        r.content().close();
        try (var s = java.nio.file.Files.list(tmp)) {
            assertThat(s.count()).isZero();
        }
    }

    @SuppressWarnings("unused")
    private static Supplier<Void> unused() {
        return null;
    }
}
