package br.com.vovolinux.m2m.files.ftp;

import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.files.GatewayException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * {@link FileGateway} sobre FTP/FTPS (Apache Commons Net). Listagem por {@code MLSD} quando o servidor anuncia
 * {@code MLST} (senão {@code LIST}), data por {@code MFMT} quando disponível, renomear por {@code RNFR/RNTO}. Não
 * preserva permissões nem links simbólicos (matriz de fidelidade, D2).
 */
public class FtpFileGateway implements FileGateway {

    private static final DateTimeFormatter MFMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final FTPClient client;
    private final Capabilities capabilities;
    private final boolean mlst;
    private final boolean mfmt;
    private final boolean rest;
    private String home;

    public FtpFileGateway(FTPClient client, Capabilities capabilities) throws IOException {
        this.client = client;
        this.mlst = client.hasFeature("MLST");
        this.mfmt = client.hasFeature("MFMT");
        this.rest = client.hasFeature("REST");
        this.capabilities = capabilities.withFreeSpace(false).withRemoteHash(false);
    }

    /** Recursos anunciados em {@code FEAT} (para o teste de conexão). */
    public boolean mfmtSupported() {
        return mfmt;
    }

    public boolean mlsdSupported() {
        return mlst;
    }

    /** Conectado e respondendo: um NOOP detecta a conexão que o servidor fechou por inatividade. */
    @Override
    public boolean isOpen() {
        try {
            return client.isConnected() && client.sendNoOp();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean supportsResume() {
        return rest;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public String homeDir() throws IOException {
        if (home == null) {
            String pwd = client.printWorkingDirectory();
            home = pwd == null ? "/" : pwd;
        }
        return home;
    }

    // ---- tradução de erros ----

    private IOException fail(String path, String operation) {
        int code = client.getReplyCode();
        String reply = String.valueOf(client.getReplyString()).strip();
        if (code == FTPReply.FILE_UNAVAILABLE) {
            String r = reply.toLowerCase();
            if (r.contains("permission") || r.contains("denied") || r.contains("not allowed")) {
                return new AccessDeniedException(path, null, reply);
            }
            if (r.contains("exist") && !r.contains("not exist") && !r.contains("no such") && operation.equals("mkdir")) {
                return new FileAlreadyExistsException(path);
            }
            return new NoSuchFileException(path, null, reply);
        }
        if (code == FTPReply.NOT_LOGGED_IN || code == FTPReply.NEED_PASSWORD) {
            return new GatewayException("conexao.autenticacao-falhou", reply, Map.of(), null);
        }
        if (code == FTPReply.SERVICE_NOT_AVAILABLE || code == 0) {
            return new GatewayException("conexao.perdida", "Conexão FTP perdida: " + reply, Map.of(), null);
        }
        return new IOException("FTP " + operation + " " + path + ": " + reply);
    }

    // ---- leitura de metadados ----

    private static String join(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }

    private FileEntry toEntry(String dir, FTPFile f) {
        FileType type = switch (f.getType()) {
            case FTPFile.DIRECTORY_TYPE -> FileType.DIRECTORY;
            case FTPFile.FILE_TYPE -> FileType.FILE;
            case FTPFile.SYMBOLIC_LINK_TYPE -> FileType.SYMLINK;
            default -> FileType.SPECIAL;
        };
        Instant mtime = f.getTimestampInstant() == null ? Instant.EPOCH : f.getTimestampInstant();
        String name = f.getName();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return new FileEntry(name, join(dir, name), type, type == FileType.FILE ? Math.max(f.getSize(), 0) : 0, mtime,
                null, type == FileType.SYMLINK ? f.getLink() : null);
    }

    @Override
    public List<FileEntry> list(String dir) throws IOException {
        FTPFile[] files = mlst ? client.mlistDir(dir) : client.listFiles(dir);
        if (files == null || (files.length == 0 && !FTPReply.isPositiveCompletion(client.getReplyCode()))) {
            if (client.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
                throw fail(dir, "list");
            }
            if (files == null) {
                throw fail(dir, "list");
            }
        }
        List<FileEntry> out = new ArrayList<>();
        for (FTPFile f : files) {
            if (f == null || f.getName() == null) {
                continue;
            }
            String n = f.getName();
            if (n.equals(".") || n.equals("..") || (f.getRawListing() != null && f.getRawListing().contains("type=cdir"))
                    || (f.getRawListing() != null && f.getRawListing().contains("type=pdir"))) {
                continue;
            }
            out.add(toEntry(dir, f));
        }
        return out;
    }

    @Override
    public FileEntry lstat(String path) throws IOException {
        if (path.equals("/")) {
            return new FileEntry("", "/", FileType.DIRECTORY, 0, Instant.EPOCH, null, null);
        }
        String parent = FsOps.parentOf(path);
        String name = FsOps.nameOf(path);
        if (mlst) {
            FTPFile f = client.mlistFile(path);
            if (f == null) {
                throw fail(path, "lstat");
            }
            FileEntry e = toEntry(parent, f);
            return new FileEntry(name, path, e.type(), e.size(), e.mtime(), null, e.linkTarget());
        }
        for (FileEntry e : list(parent)) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        throw new NoSuchFileException(path);
    }

    // ---- conteúdo ----

    /** Leitura: ao fechar antes do fim aborta a transferência; ao fechar depois, confirma o 226 do servidor. */
    private final class DataInput extends FilterInputStream {
        private boolean eof;
        private boolean closed;

        DataInput(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                eof = true;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n < 0) {
                eof = true;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (!eof) {
                try {
                    client.abort();
                } catch (IOException ignorado) {
                    // a conexão será descartada pelo chamador
                }
            }
            try {
                super.close();
            } catch (IOException ignorado) {
                // socket já fechado
            }
            try {
                boolean ok = client.completePendingCommand();
                if (!ok && eof) {
                    throw new IOException("FTP RETR não confirmado: " + String.valueOf(client.getReplyString()).strip());
                }
            } catch (IOException e) {
                if (eof) {
                    throw e;
                }
            }
        }
    }

    @Override
    public InputStream openRead(String path, long offset) throws IOException {
        if (offset > 0) {
            if (!rest) {
                throw new GatewayException("ftp.retomada-nao-suportada", "O servidor FTP não suporta REST", Map.of(), null);
            }
            client.setRestartOffset(offset);
        }
        InputStream in = client.retrieveFileStream(path);
        if (in == null) {
            throw fail(path, "retr");
        }
        return new DataInput(in);
    }

    /** Gravação: o fechamento aguarda a confirmação do servidor; falha anterior só aborta, sem mascarar o erro. */
    private final class DataOutput extends FilterOutputStream {
        private boolean failed;
        private boolean closed;

        DataOutput(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.close();
            } catch (IOException e) {
                failed = true;
            }
            if (failed) {
                try {
                    client.completePendingCommand();
                } catch (IOException ignorado) {
                    // já falhou: quem escreveu recebeu o erro original
                }
                return;
            }
            if (!client.completePendingCommand()) {
                throw new IOException("FTP STOR não confirmado: " + String.valueOf(client.getReplyString()).strip());
            }
        }
    }

    @Override
    public OutputStream openWrite(String path, long offset) throws IOException {
        OutputStream out = offset > 0 ? client.appendFileStream(path) : client.storeFileStream(path);
        if (out == null) {
            throw fail(path, offset > 0 ? "appe" : "stor");
        }
        return new DataOutput(out);
    }

    // ---- alterações ----

    @Override
    public void mkdirs(String dir) throws IOException {
        if (dir.equals("/")) {
            return;
        }
        Optional<FileEntry> existing = lstatIfExists(dir);
        if (existing.isPresent()) {
            if (!existing.get().isDirectory()) {
                throw new FileAlreadyExistsException(dir);
            }
            return;
        }
        mkdirs(FsOps.parentOf(dir));
        if (!client.makeDirectory(dir)) {
            // outra conexão pode ter criado entre a checagem e o MKD
            if (lstatIfExists(dir).filter(FileEntry::isDirectory).isEmpty()) {
                throw fail(dir, "mkdir");
            }
        }
    }

    @Override
    public void symlink(String path, String target) throws IOException {
        throw new GatewayException("ftp.links-nao-suportados", "FTP não cria links simbólicos", Map.of(), null);
    }

    @Override
    public void setMode(String path, int mode) throws IOException {
        // FTP não tem permissões Unix padronizadas: a matriz de fidelidade já avisa; nada a fazer
    }

    @Override
    public void setMtime(String path, Instant mtime) throws IOException {
        if (!mfmt) {
            return;
        }
        client.setModificationTime(path, MFMT.format(mtime));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Optional<FileEntry> existing = lstatIfExists(to);
        if (existing.isPresent()) {
            if (existing.get().isDirectory()) {
                throw new FileAlreadyExistsException(to);
            }
            // FTP não substitui no RNTO: apaga o destino e renomeia (não atômico)
            delete(to);
        }
        if (!client.rename(from, to)) {
            throw fail(from, "rename");
        }
    }

    @Override
    public void delete(String path) throws IOException {
        FileEntry e = lstat(path);
        boolean ok = e.isDirectory() ? client.removeDirectory(path) : client.deleteFile(path);
        if (!ok) {
            throw fail(path, "delete");
        }
    }

    @Override
    public OptionalLong freeSpace(String path) {
        return OptionalLong.empty();
    }

    @Override
    public Optional<String> remoteSha256(String path) {
        return Optional.empty();
    }

    /** Caminho canônico do diretório (resolve links no servidor): base da detecção de ciclos na origem FTP. */
    @Override
    public String canonicalDir(String path) throws IOException {
        String original = client.printWorkingDirectory();
        try {
            if (!client.changeWorkingDirectory(path)) {
                throw fail(path, "cwd");
            }
            String pwd = client.printWorkingDirectory();
            return pwd == null ? path : pwd;
        } finally {
            if (original != null) {
                client.changeWorkingDirectory(original);
            }
        }
    }

    @Override
    public boolean detectsCycles() {
        return true;
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } catch (IOException ignorado) {
                    // a conexão pode já estar caída
                }
                client.disconnect();
            }
        } catch (IOException ignorado) {
            // idem
        }
    }
}
