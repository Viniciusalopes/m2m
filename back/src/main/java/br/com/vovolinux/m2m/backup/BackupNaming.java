package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.profile.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Nomes dos arquivos de backup (D15): {@code <nome>[_<complemento>]_<ORIGEM>_<AAAAMMDD-HHMMSS>.tar.zst}. */
public final class BackupNaming {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BackupNaming() {
    }

    public static String regular(String profile, String complement, String sourceMachine, ZonedDateTime when,
            boolean encrypted) {
        StringBuilder n = new StringBuilder(Paths.slug(profile));
        if (complement != null && !complement.isBlank()) {
            n.append('_').append(Paths.slug(complement));
        }
        n.append('_').append(Paths.slug(sourceMachine)).append('_').append(STAMP.format(when.withZoneSameInstant(ZONE)));
        return n + (encrypted ? ".tar.zst.enc" : ".tar.zst");
    }

    /** Backup de segurança: {@code <nome>_pre-restore_<DESTINO>_<...>} ou {@code _pre-sync_}. */
    public static String safety(String profile, String kind, String destMachine, ZonedDateTime when,
            boolean encrypted) {
        String n = Paths.slug(profile) + "_" + kind + "_" + Paths.slug(destMachine) + "_"
                + STAMP.format(when.withZoneSameInstant(ZONE));
        return n + (encrypted ? ".tar.zst.enc" : ".tar.zst");
    }

    public static String partial(String finalName) {
        return finalName + ".partial";
    }

    public static String sideManifest(String finalName) {
        return finalName + ArchiveFiles.MANIFEST_SUFFIX;
    }
}
