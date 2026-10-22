package br.com.vovolinux.m2m.profile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Utilitários de caminhos POSIX (o espaço de nomes das máquinas) e de nomes de arquivo. */
public final class Paths {

    private Paths() {
    }

    /** Normaliza: absoluto, sem barras duplicadas nem '.' e sem barra final; rejeita '..'. */
    public static String normalize(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Caminho deve ser absoluto: " + path);
        }
        List<String> partes = new ArrayList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                throw new IllegalArgumentException("Caminho não pode conter '..': " + path);
            }
            partes.add(p);
        }
        return "/" + String.join("/", partes);
    }

    /** {@code child} é o próprio {@code parent} ou está dentro dele. */
    public static boolean sameOrInside(String parent, String child) {
        String p = normalize(parent);
        String c = normalize(child);
        return p.equals("/") || c.equals(p) || c.startsWith(p + "/");
    }

    /** Um é igual, pai ou filho do outro. */
    public static boolean overlap(String a, String b) {
        return sameOrInside(a, b) || sameOrInside(b, a);
    }

    /** Nome seguro para arquivo: sem acentos, espaços e símbolos viram '-' (D15). */
    public static String slug(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return n.isEmpty() ? "sem-nome" : n;
    }
}
