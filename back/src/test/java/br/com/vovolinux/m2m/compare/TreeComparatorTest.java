package br.com.vovolinux.m2m.compare;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** O comparador sobre árvores em memória: ordem, tolerância, tipos, permissões e conteúdo. */
class TreeComparatorTest {

    /** Árvore em memória: caminho relativo → nó (o conteúdo vem do mapa de hashes). */
    static class Mem implements TreeSource {
        final Map<String, Node> nodes = new TreeMap<>();
        final Map<String, String> hashes = new HashMap<>();
        boolean modes = true;
        boolean links = true;
        boolean rootExists = true;

        Mem dir(String rel) {
            nodes.put(rel, new Node(name(rel), FileType.DIRECTORY, 0, 0, 0755, null, null));
            return this;
        }

        Mem file(String rel, long size, long mtime, int mode, String hash) {
            nodes.put(rel, new Node(name(rel), FileType.FILE, size, mtime, mode, null, null));
            hashes.put(rel, hash);
            return this;
        }

        Mem link(String rel, String target) {
            nodes.put(rel, new Node(name(rel), FileType.SYMLINK, 0, 0, 0777, target, null));
            return this;
        }

        Mem special(String rel) {
            nodes.put(rel, new Node(name(rel), FileType.SPECIAL, 0, 0, 0644, null, null));
            return this;
        }

        static String name(String rel) {
            return rel.substring(rel.lastIndexOf('/') + 1);
        }

        @Override
        public Optional<Node> root() {
            return rootExists ? Optional.of(new Node("", FileType.DIRECTORY, 0, 0, 0755, null, null)) : Optional.empty();
        }

        @Override
        public List<Node> children(String relDir) {
            List<Node> out = new ArrayList<>();
            for (var e : nodes.entrySet()) {
                String parent = e.getKey().contains("/") ? e.getKey().substring(0, e.getKey().lastIndexOf('/')) : "";
                if (parent.equals(relDir)) {
                    out.add(e.getValue());
                }
            }
            return out;
        }

        @Override
        public String sha256(String relPath, Node node) throws IOException {
            return hashes.get(relPath);
        }

        @Override
        public boolean preservesMode() {
            return modes;
        }

        @Override
        public boolean preservesLinks() {
            return links;
        }

        @Override
        public String describe() {
            return "mem";
        }
    }

    /** Registra o que o comparador reporta. */
    static class Rec implements TreeComparator.Visitor {
        final List<String> onlySource = new ArrayList<>();
        final List<String> onlyTarget = new ArrayList<>();
        final Map<String, Set<TreeComparator.Reason>> different = new TreeMap<>();
        final List<String> same = new ArrayList<>();

        public void onlySource(String rel, Node n) {
            onlySource.add(rel);
        }

        public void onlyTarget(String rel, Node n) {
            onlyTarget.add(rel);
        }

        public void different(String rel, Node s, Node t, Set<TreeComparator.Reason> r) {
            different.put(rel, r);
        }

        public void same(String rel, Node s, Node t) {
            same.add(rel);
        }
    }

    private final TreeComparator cmp = new TreeComparator();

    private Rec run(Mem a, Mem b, TreeComparator.Mode mode) throws IOException {
        Rec r = new Rec();
        cmp.compare(a, b, mode, TreeComparator.MTIME_TOLERANCE_MILLIS, new CancellationToken(), r);
        return r;
    }

    @Test
    void identicosIncluindoOcultosETemporarios() throws Exception {
        Mem a = new Mem().file(".env", 3, 1000, 0644, "h1").file("x.tmp", 1, 1000, 0644, "h2").dir("sub")
                .file("sub/b", 2, 5000, 0600, "h3").dir("vazia");
        Mem b = new Mem().file(".env", 3, 1000, 0644, "h1").file("x.tmp", 1, 1000, 0644, "h2").dir("sub")
                .file("sub/b", 2, 5000, 0600, "h3").dir("vazia");
        Rec r = run(a, b, TreeComparator.Mode.FULL);
        assertThat(r.onlySource).isEmpty();
        assertThat(r.onlyTarget).isEmpty();
        assertThat(r.different).isEmpty();
        assertThat(r.same).containsExactlyInAnyOrder(".env", "x.tmp", "sub", "sub/b", "vazia");
    }

    @Test
    void soNaOrigemSoNoDestinoEDiretoriosInteirosSaoEnumerados() throws Exception {
        Mem a = new Mem().dir("novo").file("novo/a", 1, 1, 0644, "x").file("novo/b", 1, 1, 0644, "y")
                .file("comum", 1, 1, 0644, "c");
        Mem b = new Mem().dir("velho").file("velho/z", 1, 1, 0644, "z").file("comum", 1, 1, 0644, "c").file(".env", 1, 1, 0644, "e");
        Rec r = run(a, b, TreeComparator.Mode.QUICK);
        assertThat(r.onlySource).containsExactly("novo", "novo/a", "novo/b");
        assertThat(r.onlyTarget).containsExactlyInAnyOrder(".env", "velho", "velho/z");
    }

    @Test
    void mesmoTamanhoEDataMasConteudoDiferenteSoNoModoCompleto() throws Exception {
        Mem a = new Mem().file("README.md", 10, 1000, 0644, "aaa");
        Mem b = new Mem().file("README.md", 10, 1000, 0644, "bbb");
        assertThat(run(a, b, TreeComparator.Mode.QUICK).different).isEmpty();
        assertThat(run(a, b, TreeComparator.Mode.FULL).different.get("README.md"))
                .containsExactly(TreeComparator.Reason.CONTENT);
    }

    @Test
    void toleranciaDeDoisSegundosNaData() throws Exception {
        Mem a = new Mem().file("f", 5, 10_000, 0644, "h");
        assertThat(run(a, new Mem().file("f", 5, 10_800, 0644, "h"), TreeComparator.Mode.QUICK).different).isEmpty();
        assertThat(run(a, new Mem().file("f", 5, 12_000, 0644, "h"), TreeComparator.Mode.QUICK).different).isEmpty();
        assertThat(run(a, new Mem().file("f", 5, 12_001, 0644, "h"), TreeComparator.Mode.QUICK).different.get("f"))
                .containsExactly(TreeComparator.Reason.MTIME);
    }

    @Test
    void tamanhoEPermissoesDiferentes() throws Exception {
        Mem a = new Mem().file("f", 5, 1000, 0755, "h").file("g", 5, 1000, 0644, "h");
        Mem b = new Mem().file("f", 5, 1000, 0644, "h").file("g", 6, 1000, 0644, "h");
        Rec r = run(a, b, TreeComparator.Mode.QUICK);
        assertThat(r.different.get("f")).containsExactly(TreeComparator.Reason.MODE);
        assertThat(r.different.get("g")).containsExactly(TreeComparator.Reason.SIZE);
    }

    @Test
    void permissoesELinksSoSaoComparadosQuandoOsDoisLadosSuportam() throws Exception {
        Mem a = new Mem().file("f", 5, 1000, 0755, "h").link("l", "alvo1");
        Mem b = new Mem().file("f", 5, 1000, 0644, "h").link("l", "alvo2");
        b.modes = false;
        b.links = false;
        Rec r = run(a, b, TreeComparator.Mode.FULL);
        assertThat(r.different).isEmpty();
        TreeComparator.Stats st = new TreeComparator().compare(a, b, TreeComparator.Mode.FULL, 2000,
                new CancellationToken(), new Rec());
        assertThat(st.modesCompared).isFalse();
        assertThat(st.linksCompared).isFalse();
    }

    @Test
    void alvoDeLinkSoNoModoCompleto() throws Exception {
        Mem a = new Mem().link("l", "alvo1");
        Mem b = new Mem().link("l", "alvo2");
        assertThat(run(a, b, TreeComparator.Mode.QUICK).different).isEmpty();
        assertThat(run(a, b, TreeComparator.Mode.FULL).different.get("l")).containsExactly(TreeComparator.Reason.LINK);
    }

    @Test
    void conflitoDeTipoEnumeraOConteudoDoLadoDiretorio() throws Exception {
        Mem a = new Mem().file("x", 1, 1, 0644, "h");
        Mem b = new Mem().dir("x").file("x/a", 1, 1, 0644, "h").file("x/b", 1, 1, 0644, "h");
        Rec r = run(a, b, TreeComparator.Mode.QUICK);
        assertThat(r.different.get("x")).containsExactly(TreeComparator.Reason.TYPE);
        assertThat(r.onlyTarget).containsExactlyInAnyOrder("x/a", "x/b");
    }

    @Test
    void itensEspeciaisSaoIgnoradosNosDoisLados() throws Exception {
        Mem a = new Mem().special("fila").file("f", 1, 1, 0644, "h");
        Mem b = new Mem().special("socket").file("f", 1, 1, 0644, "h");
        TreeComparator.Stats st = new TreeComparator().compare(a, b, TreeComparator.Mode.QUICK, 2000,
                new CancellationToken(), new Rec());
        assertThat(st.specialsIgnored).isEqualTo(2);
        assertThat(st.onlySource + st.onlyTarget + st.different).isZero();
    }

    @Test
    void destinoInexistenteFazTudoSerSoNaOrigem() throws Exception {
        Mem a = new Mem().dir("d").file("d/f", 1, 1, 0644, "h");
        Mem b = new Mem();
        b.rootExists = false;
        Rec r = run(a, b, TreeComparator.Mode.QUICK);
        assertThat(r.onlySource).containsExactly("d", "d/f");
        assertThat(r.onlyTarget).isEmpty();
    }

    @Test
    void ordemDeVisitaEAlfabeticaEmProfundidade() throws Exception {
        Mem a = new Mem().dir("b").dir("a").file("a/z", 1, 1, 0644, "h").file("b/y", 1, 1, 0644, "h");
        Mem b = new Mem().dir("b").dir("a").file("a/z", 2, 1, 0644, "h").file("b/y", 2, 1, 0644, "h");
        Rec r = run(a, b, TreeComparator.Mode.QUICK);
        assertThat(new ArrayList<>(r.different.keySet())).containsExactly("a/z", "b/y");
    }
}
