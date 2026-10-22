package br.com.vovolinux.m2m.conflict;

import java.util.function.Predicate;

/** Resolve uma {@link ConflictAction} a partir de tamanho e data de modificação dos dois lados (D22). */
public final class ConflictPolicy {

    public enum Decision { WRITE, SKIP, RENAME, RESUME }

    /** Tamanho e data de modificação (ms) de um lado do conflito. */
    public record Meta(long size, long mtimeMillis) {
    }

    private ConflictPolicy() {
    }

    public static Decision resolve(ConflictAction action, Meta incoming, Meta existing) {
        return switch (action) {
            case OVERWRITE -> Decision.WRITE;
            case OVERWRITE_NEWER -> incoming.mtimeMillis() > existing.mtimeMillis() ? Decision.WRITE : Decision.SKIP;
            case OVERWRITE_SIZE -> incoming.size() != existing.size() ? Decision.WRITE : Decision.SKIP;
            case OVERWRITE_SIZE_OR_NEWER -> incoming.size() != existing.size()
                    || incoming.mtimeMillis() > existing.mtimeMillis() ? Decision.WRITE : Decision.SKIP;
            case RESUME -> existing.size() < incoming.size() ? Decision.RESUME : Decision.WRITE;
            case RENAME -> Decision.RENAME;
            case SKIP -> Decision.SKIP;
        };
    }

    /** {@code nome.ext} → {@code nome (1).ext}, {@code nome (2).ext}... até um nome livre. */
    public static String renamed(String name, Predicate<String> exists) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Sem nome livre para " + name);
    }
}
