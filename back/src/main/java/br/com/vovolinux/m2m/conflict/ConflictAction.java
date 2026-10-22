package br.com.vovolinux.m2m.conflict;

/** As ações de conflito do FileZilla (D22): o que fazer quando o item já existe no destino. */
public enum ConflictAction {
    OVERWRITE,
    OVERWRITE_NEWER,
    OVERWRITE_SIZE,
    OVERWRITE_SIZE_OR_NEWER,
    /** Só faz sentido em transferências (continua um arquivo parcial); na restauração equivale a sobrescrever. */
    RESUME,
    RENAME,
    SKIP
}
