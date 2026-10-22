package br.com.vovolinux.m2m.backup;

/** Estados de um backup (D7). */
public final class ArchiveStatus {

    public static final String COMPLETED = "COMPLETED";
    public static final String COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS";
    public static final String SUSPECT = "SUSPECT";
    public static final String FAILED = "FAILED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String CORRUPTED = "CORRUPTED";
    public static final String MISSING = "MISSING";

    private ArchiveStatus() {
    }

    /** Pode ser usado para restaurar. */
    public static boolean usable(String status) {
        return COMPLETED.equals(status) || COMPLETED_WITH_WARNINGS.equals(status) || SUSPECT.equals(status);
    }
}
