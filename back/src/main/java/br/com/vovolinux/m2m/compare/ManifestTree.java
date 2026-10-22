package br.com.vovolinux.m2m.compare;

import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.files.FileType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Os itens de um diretório (dir-N) do manifesto de um backup, vistos como uma árvore. O índice fica em memória
 * (um mapa pai → filhos): serve à prévia da restauração e à comparação com um destino vivo.
 */
public class ManifestTree implements TreeSource {

    private final String label;
    private final Map<String, List<Node>> byParent = new HashMap<>();
    private final boolean preservesMode;
    private final boolean preservesLinks;

    public ManifestTree(String label, boolean fullFidelity) {
        this.label = label;
        this.preservesMode = fullFidelity;
        this.preservesLinks = fullFidelity;
    }

    private Node root;

    /** Acrescenta um item do manifesto (só os do diretório desta árvore). */
    public void add(Manifest.Item item) {
        FileType type = switch (item.type()) {
            case "DIR" -> FileType.DIRECTORY;
            case "SYMLINK" -> FileType.SYMLINK;
            default -> FileType.FILE;
        };
        if (item.path().isEmpty()) {
            root = new Node("", type, 0, item.mtime(), item.mode(), null, null);
            return;
        }
        int cut = item.path().lastIndexOf('/');
        String parent = cut < 0 ? "" : item.path().substring(0, cut);
        String name = cut < 0 ? item.path() : item.path().substring(cut + 1);
        byParent.computeIfAbsent(parent, k -> new ArrayList<>())
                .add(new Node(name, type, item.size(), item.mtime(), item.mode(), item.link(), item.sha256()));
    }

    @Override
    public Optional<Node> root() {
        return Optional.ofNullable(root);
    }

    @Override
    public List<Node> children(String relDir) {
        return byParent.getOrDefault(relDir, List.of());
    }

    @Override
    public String sha256(String relPath, Node node) {
        return node.knownSha256();
    }

    @Override
    public boolean preservesMode() {
        return preservesMode;
    }

    @Override
    public boolean preservesLinks() {
        return preservesLinks;
    }

    @Override
    public String describe() {
        return label;
    }

}
