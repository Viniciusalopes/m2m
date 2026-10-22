package br.com.vovolinux.m2m.job;

/** Tipos de job; cada um tem um {@link JobHandler} registrado. */
public final class JobType {

    public static final String BACKUP = "BACKUP";
    public static final String RESTORE = "RESTORE";
    public static final String SELECTIVE_RESTORE = "SELECTIVE_RESTORE";
    public static final String SYNC = "SYNC";
    public static final String SYNC_UNDO = "SYNC_UNDO";
    public static final String VERIFY = "VERIFY";
    public static final String ARCHIVE_VERIFY = "ARCHIVE_VERIFY";
    public static final String REINDEX = "REINDEX";
    public static final String DB_BACKUP = "DB_BACKUP";

    private JobType() {
    }
}
