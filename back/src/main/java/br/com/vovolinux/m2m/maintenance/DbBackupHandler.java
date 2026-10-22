package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.notification.NotificationService;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Job DB_BACKUP (D32): {@code pg_dump} compactado em zstd, gravado pelo gateway como {@code .partial} e renomeado ao
 * fim; mantém os 7 mais recentes. Falha notifica os administradores.
 */
@Component
public class DbBackupHandler implements JobHandler {

    public static final String PREFIX = "m2m-db_";
    public static final String SUFFIX = ".sql.zst";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final MaintenanceSettings settings;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final PgDumpRunner dumper;
    private final NotificationService notifications;

    public DbBackupHandler(MaintenanceSettings settings, MachineRepository machines, GatewayPool pool,
            PgDumpRunner dumper, NotificationService notifications) {
        this.settings = settings;
        this.machines = machines;
        this.pool = pool;
        this.dumper = dumper;
        this.notifications = notifications;
    }

    @Override
    public String type() {
        return JobType.DB_BACKUP;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        try {
            executar(ctx);
        } catch (Exception e) {
            if (!ctx.token().isCancelled()) {
                notifications.notifyAdmins("DB_BACKUP_FAILED", NotificationService.ERROR,
                        Map.of("motivo", e instanceof JobFailure f ? f.codigo() : String.valueOf(e.getMessage())));
            }
            throw e;
        }
    }

    private void executar(JobContext ctx) throws Exception {
        MaintenanceSettings.DbBackup cfg = settings.dbBackup();
        Machine storage = storage(ctx.ownerId(), cfg);
        String dir = cfg.storageDir();
        String name = PREFIX + STAMP.format(ZonedDateTime.now(ZONE)) + SUFFIX;
        String partial = dir + "/" + name + ".partial";
        ctx.progress().step("BACKING_UP");
        try (GatewayPool.Lease lease = pool.acquire(storage)) {
            FileGateway gw = lease.gateway();
            gw.mkdirs(dir);
            boolean done = false;
            try {
                try (OutputStream out = gw.openWrite(partial, 0); ZstdOutputStream z = new ZstdOutputStream(out, 3)) {
                    dumper.dump(z);
                }
                ctx.token().throwIfCancelled();
                gw.rename(partial, dir + "/" + name);
                done = true;
            } catch (IOException e) {
                lease.discard();
                throw e;
            } finally {
                if (!done) {
                    try (GatewayPool.Lease limpeza = pool.acquire(storage)) {
                        limpeza.gateway().delete(partial);
                    } catch (IOException | RuntimeException ignorado) {
                        // nunca chegou a existir
                    }
                }
            }
            ctx.result().put("arquivo", name);
            ctx.result().put("bytes", gw.lstat(dir + "/" + name).size());
            ctx.progress().step("RETENTION");
            ctx.result().put("removidos", retencao(gw, dir));
        }
    }

    private Machine storage(long ownerId, MaintenanceSettings.DbBackup cfg) {
        if (cfg.storageMachineId() != null) {
            return machines.find(ownerId, cfg.storageMachineId())
                    .orElseThrow(() -> new JobFailure("perfil.armazenamento-inexistente"));
        }
        return machines.findAll(ownerId).stream().filter(m -> "LOCAL".equals(m.protocol().name())).findFirst()
                .orElseThrow(() -> new JobFailure("perfil.armazenamento-inexistente"));
    }

    private int retencao(FileGateway gw, String dir) throws IOException {
        List<FileEntry> dumps;
        try {
            dumps = gw.list(dir).stream().filter(e -> e.name().startsWith(PREFIX) && e.name().endsWith(SUFFIX))
                    .sorted(Comparator.comparing(FileEntry::name).reversed()).toList();
        } catch (NoSuchFileException e) {
            return 0;
        }
        int removidos = 0;
        for (int i = MaintenanceSettings.DB_KEEP; i < dumps.size(); i++) {
            gw.delete(dir + "/" + dumps.get(i).name());
            removidos++;
        }
        return removidos;
    }
}
