package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.settings.SettingsRepository;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import br.com.vovolinux.m2m.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

/** Configurações de manutenção da instalação: verificação periódica e backup do banco (D30, D32). */
@Service
public class MaintenanceSettings {

    public static final String VERIFY_KEY = "archive_verify";
    public static final String DB_KEY = "db_backup";
    public static final String DEFAULT_VERIFY_CRON = "0 0 4 * * SUN";
    public static final String DEFAULT_DB_DIR = "/home/backup/m2m-db";
    public static final int DB_KEEP = 7;

    public record Verify(boolean enabled, String cron) {
    }

    public record DbBackup(boolean enabled, String time, Long storageMachineId, String storageDir) {
    }

    private final SettingsRepository settings;
    private final JsonMapper json;

    public MaintenanceSettings(SettingsRepository settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public Verify verify() {
        return settings.get(VERIFY_KEY).map(v -> json.readValue(v, Verify.class))
                .orElse(new Verify(true, DEFAULT_VERIFY_CRON));
    }

    public DbBackup dbBackup() {
        return settings.get(DB_KEY).map(v -> json.readValue(v, DbBackup.class))
                .orElse(new DbBackup(true, "01:30", null, DEFAULT_DB_DIR));
    }

    public Verify saveVerify(Verify v) {
        if (v.cron() == null || !CronExpression.isValidExpression(v.cron())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "configuracao.cron-invalido");
        }
        settings.put(VERIFY_KEY, json.writeValueAsString(v));
        return v;
    }

    public DbBackup saveDbBackup(DbBackup d) {
        try {
            LocalTime.parse(d.time());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "perfil.horario-invalido");
        }
        if (d.storageDir() == null || !d.storageDir().startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "configuracao.diretorio-invalido");
        }
        settings.put(DB_KEY, json.writeValueAsString(d));
        return d;
    }
}
