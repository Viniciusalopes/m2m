package br.com.vovolinux.m2m.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.files.local.LocalFileGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** O "host" é um diretório temporário: base/home/... faz o papel de /hostfs/home/... */
class LocalFileGatewayTest extends FileGatewayContract {

    private Path base;
    private LocalFileGateway gw;
    private String root;

    @BeforeEach
    void preparar() throws IOException {
        base = Files.createTempDirectory("m2m-local-");
        Files.createDirectories(base.resolve("home/teste"));
        Files.createDirectories(base.resolve("etc"));
        gw = new LocalFileGateway(base, List.of("/home"));
        root = "/home/teste";
    }

    @AfterEach
    void limpar() throws IOException {
        try (var s = Files.walk(base)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    @Override
    protected FileGateway gateway() {
        return gw;
    }

    @Override
    protected String root() {
        return root;
    }

    @Override
    protected Path hostPath(String gatewayPath) {
        return base.resolve(gatewayPath.substring(1));
    }

    @Test
    void caminhoForaDasRaizesEhRecusado() {
        assertThatThrownBy(() -> gw.list("/etc")).isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).codigo()).isEqualTo("local.fora-das-raizes");
        assertThatThrownBy(() -> gw.list("/home/../etc")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> gw.lstat("relativo/x")).isInstanceOf(GatewayException.class);
    }

    @Test
    void linkNoAncestralNaoPermiteEscaparDaMontagem() throws Exception {
        Path fora = Files.createTempDirectory("m2m-fora-");
        Files.writeString(fora.resolve("segredo.txt"), "nao pode ler");
        Files.createSymbolicLink(base.resolve("home/teste/escape"), fora);
        try {
            assertThatThrownBy(() -> gw.openRead("/home/teste/escape/segredo.txt", 0))
                    .isInstanceOf(GatewayException.class);
            // listar não segue o link: ele não é um diretório
            assertThatThrownBy(() -> gw.list("/home/teste/escape")).isInstanceOf(IOException.class);
            // o próprio link continua visível como link
            assertThat(gw.lstat("/home/teste/escape").type()).isEqualTo(FileType.SYMLINK);
        } finally {
            Files.delete(fora.resolve("segredo.txt"));
            Files.delete(fora);
        }
    }

    @Test
    void traduzCaminhosDoHostParaOMontadoESemVazarOPrefixo() throws Exception {
        Files.writeString(base.resolve("home/teste/a.txt"), "x");
        assertThat(gw.list("/home/teste")).extracting(FileEntry::path).containsExactly("/home/teste/a.txt");
    }
}
