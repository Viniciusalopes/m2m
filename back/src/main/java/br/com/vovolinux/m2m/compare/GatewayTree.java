package br.com.vovolinux.m2m.compare;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Árvore viva numa máquina, acessada por um {@link FileGateway}. */
public class GatewayTree implements TreeSource {

    private final FileGateway gw;
    private final String root;
    private final String label;

    public GatewayTree(FileGateway gw, String root, String label) {
        this.gw = gw;
        this.root = root.endsWith("/") && root.length() > 1 ? root.substring(0, root.length() - 1) : root;
        this.label = label;
    }

    private String abs(String rel) {
        return rel.isEmpty() ? root : root + "/" + rel;
    }

    @Override
    public Optional<Node> root() throws IOException {
        try {
            return Optional.of(Node.of(gw.lstat(root)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Node> children(String relDir) throws IOException {
        try {
            return gw.list(abs(relDir)).stream().map(Node::of).toList();
        } catch (NoSuchFileException e) {
            return List.of();
        }
    }

    @Override
    public String sha256(String relPath, Node node) throws IOException {
        String path = abs(relPath);
        if (gw.capabilities().remoteHash()) {
            Optional<String> h = gw.remoteSha256(path);
            if (h.isPresent()) {
                return h.get();
            }
        }
        try (InputStream in = gw.openRead(path, 0)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean preservesMode() {
        return gw.capabilities().preservesMode();
    }

    @Override
    public boolean preservesLinks() {
        return gw.capabilities().preservesLinks();
    }

    @Override
    public String describe() {
        return label + ":" + root;
    }
}
