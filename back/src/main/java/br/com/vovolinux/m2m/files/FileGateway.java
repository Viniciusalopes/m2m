package br.com.vovolinux.m2m.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Acesso uniforme aos arquivos de uma máquina (LOCAL, SFTP, FTP ou FTPS). Todos os caminhos são POSIX absolutos
 * no espaço de nomes da máquina; nenhuma operação segue links simbólicos, exceto a leitura do conteúdo de um
 * arquivo regular. Uma instância não é segura para uso concorrente: use uma por thread (via {@code GatewayPool}).
 */
public interface FileGateway extends AutoCloseable {

    Capabilities capabilities();

    /** Diretório inicial para a navegação (home no SFTP; primeira raiz no LOCAL). */
    default String homeDir() throws IOException {
        return "/";
    }

    /** Lista o diretório sem seguir links; ordem não garantida. */
    List<FileEntry> list(String dir) throws IOException;

    /** Metadados do item sem seguir link; {@link NoSuchFileException} se não existir. */
    FileEntry lstat(String path) throws IOException;

    default Optional<FileEntry> lstatIfExists(String path) throws IOException {
        try {
            return Optional.of(lstat(path));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /** Abre o conteúdo de um arquivo regular a partir de {@code offset}. */
    InputStream openRead(String path, long offset) throws IOException;

    /**
     * Abre um arquivo para gravação. {@code offset == 0} cria ou trunca; {@code offset > 0} continua a gravação a
     * partir desse deslocamento sem truncar (retomada).
     */
    OutputStream openWrite(String path, long offset) throws IOException;

    /** Cria o diretório e os ancestrais que faltarem; sem erro se já existir. */
    void mkdirs(String dir) throws IOException;

    void symlink(String path, String target) throws IOException;

    void setMode(String path, int mode) throws IOException;

    void setMtime(String path, Instant mtime) throws IOException;

    /** Renomeia, substituindo o destino se for arquivo (troca atômica quando o protocolo permitir). */
    void rename(String from, String to) throws IOException;

    /** Remove arquivo, link ou diretório vazio. */
    void delete(String path) throws IOException;

    /** Espaço livre em bytes no sistema de arquivos que contém {@code path}, quando for possível medir. */
    OptionalLong freeSpace(String path) throws IOException;

    /** Espaço livre e total do sistema de arquivos que contém {@code path}; vazio se o protocolo não permite medir. */
    default Optional<SpaceInfo> space(String path) throws IOException {
        return Optional.empty();
    }

    /** SHA-256 (hex) calculado no próprio servidor, quando a máquina permitir; vazio caso contrário. */
    Optional<String> remoteSha256(String path) throws IOException;

    /** Mesma exceção para o cancelamento por fechamento forçado: leituras/gravações em curso falham com IOException. */
    @Override
    void close();
}
