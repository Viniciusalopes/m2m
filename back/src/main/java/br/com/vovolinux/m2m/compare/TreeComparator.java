package br.com.vovolinux.m2m.compare;

import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;

/**
 * Comparador origem × destino em streaming ordenado (D11): percorre os dois lados diretório a diretório, em ordem de
 * nome, e informa o que existe só na origem, só no destino e o que difere. Usado pela verificação, pela prévia da
 * restauração, pela sincronização e pela comparação de pastas da tela de transferência.
 */
@Component
public class TreeComparator {

    public enum Mode { QUICK, FULL }

    public enum Reason { TYPE, SIZE, MTIME, MODE, CONTENT, LINK }

    /** Tolerância de data de modificação: precisão diferente entre protocolos (SFTP/FTP, segundos inteiros). */
    public static final long MTIME_TOLERANCE_MILLIS = 2000;

    public interface Visitor {
        void onlySource(String rel, Node source);

        void onlyTarget(String rel, Node target);

        void different(String rel, Node source, Node target, Set<Reason> reasons);

        void same(String rel, Node source, Node target);
    }

    /** Contagens finais e o que não pôde ser comparado. */
    public static final class Stats {
        public long filesCompared;
        public long dirsCompared;
        public long onlySource;
        public long onlyTarget;
        public long different;
        public long specialsIgnored;
        /** Links simbólicos de um lado cujo outro lado não os preserva (ex.: FTP): não são diferença. */
        public long linksSkipped;
        public boolean modesCompared;
        public boolean linksCompared;
    }

    /** Par de diretórios a comparar (caminho relativo; um dos lados pode não existir). */
    private record Pair(String rel, boolean sourceExists, boolean targetExists) {
    }

    public Stats compare(TreeSource source, TreeSource target, Mode mode, long mtimeToleranceMillis,
            CancellationToken token, Visitor visitor) throws IOException {
        Stats stats = new Stats();
        boolean compareModes = source.preservesMode() && target.preservesMode();
        boolean compareLinks = source.preservesLinks() && target.preservesLinks();
        stats.modesCompared = compareModes;
        stats.linksCompared = compareLinks;

        var sRoot = source.root();
        var tRoot = target.root();
        if (sRoot.isEmpty() && tRoot.isEmpty()) {
            return stats;
        }
        ArrayDeque<Pair> stack = new ArrayDeque<>();
        stack.push(new Pair("", sRoot.isPresent(), tRoot.isPresent()));
        while (!stack.isEmpty()) {
            token.throwIfCancelled();
            Pair p = stack.pop();
            List<Node> a = p.sourceExists() ? sorted(source.children(p.rel())) : List.of();
            List<Node> b = p.targetExists() ? sorted(target.children(p.rel())) : List.of();
            List<Pair> subdirs = new ArrayList<>();
            int i = 0;
            int j = 0;
            while (i < a.size() || j < b.size()) {
                token.throwIfCancelled();
                Node na = i < a.size() ? a.get(i) : null;
                Node nb = j < b.size() ? b.get(j) : null;
                int c = na == null ? 1 : nb == null ? -1 : na.name().compareTo(nb.name());
                if (c < 0) {
                    i++;
                    if (na.type() == FileType.SPECIAL) {
                        stats.specialsIgnored++;
                        continue;
                    }
                    if (na.type() == FileType.SYMLINK && !target.preservesLinks()) {
                        stats.linksSkipped++;
                        continue;
                    }
                    String rel = join(p.rel(), na.name());
                    stats.onlySource++;
                    visitor.onlySource(rel, na);
                    if (na.isDir()) {
                        descend(source, rel, true, stats, token, visitor);
                    }
                } else if (c > 0) {
                    j++;
                    if (nb.type() == FileType.SPECIAL) {
                        stats.specialsIgnored++;
                        continue;
                    }
                    if (nb.type() == FileType.SYMLINK && !source.preservesLinks()) {
                        stats.linksSkipped++;
                        continue;
                    }
                    String rel = join(p.rel(), nb.name());
                    stats.onlyTarget++;
                    visitor.onlyTarget(rel, nb);
                    if (nb.isDir()) {
                        descend(target, rel, false, stats, token, visitor);
                    }
                } else {
                    i++;
                    j++;
                    if (na.type() == FileType.SPECIAL || nb.type() == FileType.SPECIAL) {
                        stats.specialsIgnored++;
                        continue;
                    }
                    String rel = join(p.rel(), na.name());
                    compareBoth(source, target, rel, na, nb, mode, mtimeToleranceMillis, compareModes, compareLinks,
                            stats, subdirs, token, visitor);
                }
            }
            // visita os subdiretórios em ordem alfabética (a pilha inverte)
            for (int k = subdirs.size() - 1; k >= 0; k--) {
                stack.push(subdirs.get(k));
            }
        }
        return stats;
    }

    private void compareBoth(TreeSource source, TreeSource target, String rel, Node a, Node b, Mode mode,
            long tolerance, boolean compareModes, boolean compareLinks, Stats stats, List<Pair> sub,
            CancellationToken token, Visitor visitor) throws IOException {
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (a.type() != b.type()) {
            reasons.add(Reason.TYPE);
            stats.different++;
            visitor.different(rel, a, b, reasons);
            // o que estava do outro lado do conflito de tipo também conta (será criado/excluído)
            if (b.isDir()) {
                descend(target, rel, false, stats, token, visitor);
            }
            if (a.isDir()) {
                descend(source, rel, true, stats, token, visitor);
            }
            return;
        }
        switch (a.type()) {
            case DIRECTORY -> {
                stats.dirsCompared++;
                if (compareModes && a.mode() != null && b.mode() != null && (a.mode() & 0777) != (b.mode() & 0777)) {
                    reasons.add(Reason.MODE);
                }
                sub.add(new Pair(rel, true, true));
            }
            case FILE -> {
                stats.filesCompared++;
                if (a.size() != b.size()) {
                    reasons.add(Reason.SIZE);
                }
                if (Math.abs(a.mtimeMillis() - b.mtimeMillis()) > tolerance) {
                    reasons.add(Reason.MTIME);
                }
                if (compareModes && a.mode() != null && b.mode() != null && (a.mode() & 0777) != (b.mode() & 0777)) {
                    reasons.add(Reason.MODE);
                }
                if (mode == Mode.FULL && !reasons.contains(Reason.SIZE)) {
                    if (!sameContent(source, target, rel, a, b)) {
                        reasons.add(Reason.CONTENT);
                    }
                }
            }
            case SYMLINK -> {
                if (compareLinks && mode == Mode.FULL && !java.util.Objects.equals(a.linkTarget(), b.linkTarget())) {
                    reasons.add(Reason.LINK);
                }
            }
            default -> {
            }
        }
        if (reasons.isEmpty()) {
            visitor.same(rel, a, b);
        } else {
            stats.different++;
            visitor.different(rel, a, b, reasons);
        }
    }

    private boolean sameContent(TreeSource source, TreeSource target, String rel, Node a, Node b) throws IOException {
        // os dois hashes são calculados ao mesmo tempo (podem estar em máquinas diferentes)
        CompletableFuture<String> ha = CompletableFuture.supplyAsync(() -> hash(source, rel, a),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        CompletableFuture<String> hb = CompletableFuture.supplyAsync(() -> hash(target, rel, b),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        try {
            return ha.join().equals(hb.join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof java.io.UncheckedIOException u) {
                throw u.getCause();
            }
            throw e;
        }
    }

    private static String hash(TreeSource t, String rel, Node n) {
        try {
            return t.sha256(rel, n);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Enumera todo o conteúdo de um diretório que só existe de um lado (ou que mudou de tipo). */
    private void descend(TreeSource side, String relDir, boolean isSource, Stats stats, CancellationToken token,
            Visitor visitor) throws IOException {
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(relDir);
        while (!stack.isEmpty()) {
            token.throwIfCancelled();
            String dir = stack.pop();
            List<Node> kids = sorted(side.children(dir));
            List<String> subs = new ArrayList<>();
            for (Node n : kids) {
                if (n.type() == FileType.SPECIAL) {
                    stats.specialsIgnored++;
                    continue;
                }
                String rel = join(dir, n.name());
                if (isSource) {
                    stats.onlySource++;
                    visitor.onlySource(rel, n);
                } else {
                    stats.onlyTarget++;
                    visitor.onlyTarget(rel, n);
                }
                if (n.isDir()) {
                    subs.add(rel);
                }
            }
            for (int k = subs.size() - 1; k >= 0; k--) {
                stack.push(subs.get(k));
            }
        }
    }

    private static List<Node> sorted(List<Node> nodes) {
        List<Node> copy = new ArrayList<>(nodes);
        copy.sort(Comparator.comparing(Node::name));
        return copy;
    }

    private static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }
}
