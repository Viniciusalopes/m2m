package br.com.vovolinux.m2m.files.local;

import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.GatewayException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Máquina LOCAL: o host do m2m, visto pelo container em {@code <base>/<caminho do host>} (ex.: {@code /hostfs/home}).
 * A interface e o banco guardam caminhos do host; a tradução acontece aqui, restrita às raízes configuradas (D3).
 */
public class LocalFileGateway implements FileGateway {

    private static final int BUFFER = 1 << 16;

    private final Path base;
    private final List<String> roots;

    /**
     * @param base  diretório onde as raízes do host estão montadas (ex.: {@code /hostfs})
     * @param roots raízes do host acessíveis (ex.: {@code /home}); caminhos fora delas são recusados
     */
    public LocalFileGateway(Path base, List<String> roots) {
        try {
            this.base = base.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Diretório base do acesso LOCAL inexistente: " + base, e);
        }
        this.roots = roots.stream().map(LocalFileGateway::normalizeRoot).toList();
    }

    private static String normalizeRoot(String r) {
        String n = Path.of(r).normalize().toString();
        return n.length() > 1 && n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.full().withRemoteHash(true);
    }

    @Override
    public String homeDir() {
        return roots.get(0);
    }

    // ---- tradução e segurança de caminhos ----

    /** Valida o caminho do host e devolve o caminho dentro do container, sem seguir o último componente. */
    Path resolve(String hostPath) throws IOException {
        if (hostPath == null || !hostPath.startsWith("/")) {
            throw new GatewayException("local.caminho-invalido", "Caminho deve ser absoluto: " + hostPath);
        }
        Path normalized = Path.of(hostPath).normalize();
        String n = normalized.toString();
        boolean dentro = roots.stream().anyMatch(r -> r.equals("/") || n.equals(r) || n.startsWith(r + "/"));
        if (!dentro) {
            throw new GatewayException("local.fora-das-raizes", "Caminho fora das raízes permitidas: " + hostPath,
                    Map.of("caminho", hostPath), null);
        }
        Path real = base.resolve(n.substring(1));
        Path parent = real.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            // Um link em algum ancestral não pode levar a um lugar fora do que foi montado.
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(base)) {
                throw new GatewayException("local.fora-das-raizes", "Caminho escapa das raízes: " + hostPath,
                        Map.of("caminho", hostPath), null);
            }
        }
        return real;
    }

    private String hostPath(Path real) {
        return "/" + base.relativize(real).toString();
    }

    // ---- leitura ----

    @Override
    public List<FileEntry> list(String dir) throws IOException {
        Path d = resolve(dir);
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(dir);
        }
        if (!d.toRealPath().startsWith(base)) {
            throw new GatewayException("local.fora-das-raizes", "Caminho escapa das raízes: " + dir);
        }
        List<FileEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(d)) {
            for (Path p : stream) {
                try {
                    out.add(entry(p));
                } catch (NoSuchFileException e) {
                    // removido durante a listagem
                }
            }
        }
        return out;
    }

    @Override
    public FileEntry lstat(String path) throws IOException {
        return entry(resolve(path));
    }

    private FileEntry entry(Path p) throws IOException {
        PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        FileType type;
        String target = null;
        if (a.isSymbolicLink()) {
            type = FileType.SYMLINK;
            target = Files.readSymbolicLink(p).toString();
        } else if (a.isRegularFile()) {
            type = FileType.FILE;
        } else if (a.isDirectory()) {
            type = FileType.DIRECTORY;
        } else {
            type = FileType.SPECIAL;
        }
        String name = p.getFileName() == null ? "/" : p.getFileName().toString();
        return new FileEntry(name, hostPath(p), type, type == FileType.FILE ? a.size() : 0,
                a.lastModifiedTime().toInstant(), mode(a.permissions()), target);
    }

    static int mode(Set<PosixFilePermission> perms) {
        int m = 0;
        for (PosixFilePermission p : perms) {
            m |= switch (p) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return m;
    }

    static Set<PosixFilePermission> perms(int mode) {
        Set<PosixFilePermission> s = java.util.EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) s.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) s.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) s.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 040) != 0) s.add(PosixFilePermission.GROUP_READ);
        if ((mode & 020) != 0) s.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 010) != 0) s.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 04) != 0) s.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 02) != 0) s.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 01) != 0) s.add(PosixFilePermission.OTHERS_EXECUTE);
        return s;
    }

    @Override
    public InputStream openRead(String path, long offset) throws IOException {
        Path p = resolve(path);
        requireRegular(p, path);
        FileChannel ch = FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            if (offset > 0) {
                ch.position(offset);
            }
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return Channels.newInputStream(ch);
    }

    /** Abrir um FIFO bloqueia para sempre: só arquivos regulares são abertos. */
    private static void requireRegular(Path p, String original) throws IOException {
        PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) {
            throw new GatewayException("local.nao-eh-arquivo", "Não é um arquivo regular: " + original,
                    Map.of("caminho", original), null);
        }
    }

    // ---- escrita ----

    @Override
    public OutputStream openWrite(String path, long offset) throws IOException {
        Path p = resolve(path);
        FileChannel ch;
        if (offset <= 0) {
            ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        } else {
            ch = FileChannel.open(p, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                ch.truncate(offset); // descarta qualquer sobra além do que foi confirmado
                ch.position(offset);
            } catch (IOException e) {
                ch.close();
                throw e;
            }
        }
        return Channels.newOutputStream(ch);
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        Files.createDirectories(resolve(dir));
    }

    @Override
    public void symlink(String path, String target) throws IOException {
        Files.createSymbolicLink(resolve(path), Path.of(target));
    }

    @Override
    public void setMode(String path, int mode) throws IOException {
        Path p = resolve(path);
        if (Files.isSymbolicLink(p)) {
            return; // permissões de link não existem em Linux
        }
        PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        v.setPermissions(perms(mode));
    }

    @Override
    public void setMtime(String path, Instant mtime) throws IOException {
        Path p = resolve(path);
        BasicFileAttributeView v = Files.getFileAttributeView(p, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        v.setTimes(FileTime.from(mtime), null, null);
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Path src = resolve(from);
        Path dst = resolve(to);
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        Files.delete(resolve(path));
    }

    @Override
    public OptionalLong freeSpace(String path) throws IOException {
        Path p = resolve(path);
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            p = p.getParent();
        }
        return p == null ? OptionalLong.empty() : OptionalLong.of(Files.getFileStore(p).getUsableSpace());
    }

    @Override
    public Optional<br.com.vovolinux.m2m.files.SpaceInfo> space(String path) throws IOException {
        Path p = resolve(path);
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            p = p.getParent();
        }
        if (p == null) {
            return Optional.empty();
        }
        var store = Files.getFileStore(p);
        return Optional.of(new br.com.vovolinux.m2m.files.SpaceInfo(store.getUsableSpace(), store.getTotalSpace()));
    }

    @Override
    public Optional<String> remoteSha256(String path) throws IOException {
        Path p = resolve(path);
        requireRegular(p, path);
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return Optional.of(HexFormat.of().formatHex(md.digest()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        // sem recursos
    }

    /** Traduz AccessDenied em erro com código estável (útil para o teste de conexão). */
    public static boolean isAccessDenied(Throwable t) {
        return t instanceof AccessDeniedException;
    }
}
