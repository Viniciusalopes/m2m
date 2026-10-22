package br.com.vovolinux.m2m.sync;

import java.nio.file.Path;
import java.util.List;

/**
 * Plano de uma sincronização: lista de operações em arquivo NDJSON (ordem de pré-ordem por diretório) e contagens.
 */
public record SyncPlan(Path file, List<DirSummary> directories, long create, long update, long replace, long delete,
        long sourceFiles, long sourceBytes, long specialsIgnored, boolean modesCompared, boolean linksCompared) {

    /** Contagens de um diretório do perfil. */
    public record DirSummary(int dir, String sourcePath, String targetPath, boolean sourceExists, boolean targetExists,
            long create, long update, long replace, long delete, long sourceItems, long targetItems) {
    }

    public boolean empty() {
        return create + update + replace + delete == 0;
    }

    /** Itens que serão sobrescritos ou excluídos no destino (conteúdo do backup de segurança). */
    public long destructive() {
        return update + replace + delete;
    }
}
