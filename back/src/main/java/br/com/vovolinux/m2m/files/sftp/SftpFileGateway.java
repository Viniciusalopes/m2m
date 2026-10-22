package br.com.vovolinux.m2m.files.sftp;

import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.GatewayException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.CopyMode;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatExtensionInfo;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/** Máquina SFTP: permissões, links simbólicos e datas preservados; hash remoto via canal exec quando disponível. */
public class SftpFileGateway implements FileGateway {

    private static final Duration EXEC_TIMEOUT = Duration.ofMinutes(10);

    private final ClientSession session;
    private final SftpClient sftp;
    private final Capabilities capabilities;

    public SftpFileGateway(ClientSession session, Capabilities capabilities) throws IOException {
        this.session = session;
        this.capabilities = capabilities;
        this.sftp = SftpClientFactory.instance().createSftpClient(session);
    }

    public ClientSession session() {
        return session;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public String homeDir() throws IOException {
        return sftp.canonicalPath(".");
    }

    // ---- tradução de erros ----

    private static IOException translate(IOException e, String path) {
        if (e instanceof SftpException se) {
            return switch (se.getStatus()) {
                case SftpConstants.SSH_FX_NO_SUCH_FILE, SftpConstants.SSH_FX_NO_SUCH_PATH -> {
                    NoSuchFileException n = new NoSuchFileException(path);
                    n.initCause(e);
                    yield n;
                }
                case SftpConstants.SSH_FX_PERMISSION_DENIED -> {
                    AccessDeniedException a = new AccessDeniedException(path);
                    a.initCause(e);
                    yield a;
                }
                case SftpConstants.SSH_FX_FILE_ALREADY_EXISTS -> {
                    FileAlreadyExistsException f = new FileAlreadyExistsException(path);
                    f.initCause(e);
                    yield f;
                }
                default -> e;
            };
        }
        return e;
    }

    // ---- leitura ----

    @Override
    public List<FileEntry> list(String dir) throws IOException {
        try {
            if (!sftp.lstat(dir).isDirectory()) {
                throw new NoSuchFileException(dir);
            }
            List<FileEntry> out = new ArrayList<>();
            for (DirEntry e : sftp.readDir(dir)) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                out.add(toEntry(join(dir, name), name, e.getAttributes()));
            }
            return out;
        } catch (IOException e) {
            throw translate(e, dir);
        }
    }

    @Override
    public FileEntry lstat(String path) throws IOException {
        try {
            return toEntry(path, nameOf(path), sftp.lstat(path));
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    private FileEntry toEntry(String path, String name, Attributes a) throws IOException {
        FileType type;
        String target = null;
        if (a.isSymbolicLink()) {
            type = FileType.SYMLINK;
            target = sftp.readLink(path);
        } else if (a.isRegularFile()) {
            type = FileType.FILE;
        } else if (a.isDirectory()) {
            type = FileType.DIRECTORY;
        } else {
            type = FileType.SPECIAL;
        }
        FileTime mt = a.getModifyTime();
        Instant mtime = mt == null ? Instant.EPOCH : mt.toInstant();
        Integer mode = a.getPermissions() == 0 && type == FileType.SPECIAL ? null : (a.getPermissions() & 0777);
        return new FileEntry(name, path, type, type == FileType.FILE ? a.getSize() : 0, mtime, mode, target);
    }

    @Override
    public InputStream openRead(String path, long offset) throws IOException {
        try {
            if (!sftp.lstat(path).isRegularFile()) {
                throw new GatewayException("sftp.nao-eh-arquivo", "Não é um arquivo regular: " + path,
                        Map.of("caminho", path), null);
            }
            InputStream in = sftp.read(path, OpenMode.Read);
            if (offset > 0) {
                long restante = offset;
                while (restante > 0) {
                    long n = in.skip(restante);
                    if (n <= 0) {
                        in.close();
                        throw new IOException("Deslocamento além do fim de " + path);
                    }
                    restante -= n;
                }
            }
            return in;
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    // ---- escrita ----

    @Override
    public OutputStream openWrite(String path, long offset) throws IOException {
        try {
            if (offset <= 0) {
                return sftp.write(path, OpenMode.Create, OpenMode.Write, OpenMode.Truncate);
            }
            // Retomada: descarta o que passar do confirmado e continua anexando.
            sftp.setStat(path, new Attributes().size(offset));
            return sftp.write(path, OpenMode.Write, OpenMode.Append);
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        String atual = "";
        for (String parte : dir.split("/")) {
            if (parte.isEmpty()) {
                continue;
            }
            atual = atual + "/" + parte;
            try {
                if (sftp.lstat(atual).isDirectory()) {
                    continue;
                }
                throw new FileAlreadyExistsException(atual);
            } catch (SftpException e) {
                if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                    throw translate(e, atual);
                }
            }
            try {
                sftp.mkdir(atual);
            } catch (SftpException e) {
                // outra thread pode ter criado no meio tempo
                try {
                    if (sftp.lstat(atual).isDirectory()) {
                        continue;
                    }
                } catch (IOException ignorado) {
                    // devolve o erro original
                }
                throw translate(e, atual);
            }
        }
    }

    @Override
    public void symlink(String path, String target) throws IOException {
        try {
            sftp.symLink(path, target);
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    @Override
    public void setMode(String path, int mode) throws IOException {
        try {
            if (sftp.lstat(path).isSymbolicLink()) {
                return;
            }
            sftp.setStat(path, new Attributes().perms(mode));
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    @Override
    public void setMtime(String path, Instant mtime) throws IOException {
        try {
            if (sftp.lstat(path).isSymbolicLink()) {
                return; // SFTP v3 não tem lsetstat: mudar a data seguiria o link
            }
            FileTime ft = FileTime.from(mtime);
            sftp.setStat(path, new Attributes().accessTime(ft).modifyTime(ft));
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        try {
            OpenSSHPosixRenameExtension posix = sftp.getExtension(OpenSSHPosixRenameExtension.class);
            if (posix != null && posix.isSupported()) {
                posix.posixRename(from, to); // troca atômica, substitui o destino
                return;
            }
            try {
                sftp.rename(from, to, CopyMode.Overwrite);
            } catch (SftpException e) {
                // servidores sem substituição: apaga o destino e renomeia (não atômico)
                if (sftp.lstat(to).isDirectory()) {
                    throw e;
                }
                sftp.remove(to);
                sftp.rename(from, to);
            }
        } catch (IOException e) {
            throw translate(e, from);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            if (sftp.lstat(path).isDirectory()) {
                sftp.rmdir(path);
            } else {
                sftp.remove(path);
            }
        } catch (IOException e) {
            throw translate(e, path);
        }
    }

    // ---- recursos do servidor ----

    @Override
    public OptionalLong freeSpace(String path) throws IOException {
        OpenSSHStatPathExtension ext = sftp.getExtension(OpenSSHStatPathExtension.class);
        if (ext == null || !ext.isSupported()) {
            return OptionalLong.empty();
        }
        String p = path;
        while (true) {
            try {
                OpenSSHStatExtensionInfo info = ext.stat(p);
                return OptionalLong.of(info.f_bavail * info.f_frsize);
            } catch (SftpException e) {
                int i = p.lastIndexOf('/');
                if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE || i <= 0) {
                    return OptionalLong.empty();
                }
                p = p.substring(0, i);
            }
        }
    }

    /** Hash calculado no servidor pelo canal exec; vazio quando a máquina não permite executar comandos. */
    @Override
    public Optional<String> remoteSha256(String path) throws IOException {
        if (!capabilities.remoteHash()) {
            return Optional.empty();
        }
        String cmd = "sha256sum -- " + shellQuote(path);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String out = session.executeRemoteCommand(cmd, err, StandardCharsets.UTF_8, EXEC_TIMEOUT);
        String hash = out.trim().split("\\s+")[0];
        return hash.matches("[0-9a-f]{64}") ? Optional.of(hash) : Optional.empty();
    }

    /** Testa se o canal exec funciona nesta conta. */
    public boolean execSupported() {
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            String out = session.executeRemoteCommand("echo m2m-exec", err, StandardCharsets.UTF_8,
                    Duration.ofSeconds(10));
            return out.trim().equals("m2m-exec");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public boolean statvfsSupported() {
        try {
            OpenSSHStatPathExtension ext = sftp.getExtension(OpenSSHStatPathExtension.class);
            return ext != null && ext.isSupported();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 || path.length() == 1 ? path : path.substring(i + 1);
    }

    /** Fechamento forçado: interrompe imediatamente leituras e gravações em curso (cancelamento de emergência). */
    @Override
    public void close() {
        try {
            sftp.close();
        } catch (IOException | RuntimeException ignorado) {
            // a sessão é fechada de qualquer forma
        }
        session.close(true);
    }

}
