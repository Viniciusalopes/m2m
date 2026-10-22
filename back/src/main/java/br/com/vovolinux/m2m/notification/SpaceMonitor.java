package br.com.vovolinux.m2m.notification;

import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.files.SpaceInfo;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Espaço livre dos armazenamentos (D27): conferido a cada job e diariamente; abaixo de 10% gera notificação, no
 * máximo uma por armazenamento por dia. Também alimenta o painel (com cache curto).
 */
@Component
public class SpaceMonitor {

    public static final double LIMITE_PERCENTUAL = 10.0;
    private static final Logger log = LoggerFactory.getLogger(SpaceMonitor.class);
    private static final long CACHE_MILLIS = 60_000;

    /** Armazenamento de algum perfil. */
    public record Storage(long ownerId, long machineId, String dir) {
    }

    public record Measurement(Storage storage, String machineName, Optional<SpaceInfo> space, String error) {
    }

    private record Cached(long at, Optional<SpaceInfo> space, String error) {
    }

    private final JdbcClient jdbc;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final NotificationService notifications;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public SpaceMonitor(JdbcClient jdbc, MachineRepository machines, GatewayPool pool,
            NotificationService notifications) {
        this.jdbc = jdbc;
        this.machines = machines;
        this.pool = pool;
        this.notifications = notifications;
    }

    /** Armazenamentos distintos (máquina + diretório) dos perfis de todos os usuários. */
    public List<Storage> storages() {
        return jdbc.sql("select distinct owner_id, storage_machine_id, storage_dir from profile")
                .query((rs, i) -> new Storage(rs.getLong(1), rs.getLong(2), rs.getString(3))).list();
    }

    public Measurement measure(Storage s, boolean useCache) {
        String key = s.machineId() + ":" + s.dir();
        Cached c = cache.get(key);
        Machine m = machines.find(s.ownerId(), s.machineId()).orElse(null);
        String name = m == null ? "?" : m.name();
        if (m == null) {
            return new Measurement(s, name, Optional.empty(), "maquina-removida");
        }
        if (useCache && c != null && System.currentTimeMillis() - c.at() < CACHE_MILLIS) {
            return new Measurement(s, name, c.space(), c.error());
        }
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            Optional<SpaceInfo> space;
            try {
                space = lease.gateway().space(s.dir());
            } catch (IOException e) {
                lease.discard();
                throw e;
            }
            cache.put(key, new Cached(System.currentTimeMillis(), space, null));
            return new Measurement(s, name, space, null);
        } catch (IOException | RuntimeException e) {
            String err = e instanceof br.com.vovolinux.m2m.files.GatewayException g ? g.codigo()
                    : String.valueOf(e.getMessage());
            cache.put(key, new Cached(System.currentTimeMillis(), Optional.empty(), err));
            return new Measurement(s, name, Optional.empty(), err);
        }
    }

    /** Notifica se o espaço livre está abaixo do limite (no máximo uma por armazenamento por dia). */
    public boolean evaluate(long ownerId, String machineName, String dir, SpaceInfo space) {
        if (space.freePercent() >= LIMITE_PERCENTUAL) {
            return false;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("armazenamento", machineName + ":" + dir);
        p.put("maquina", machineName);
        p.put("dir", dir);
        p.put("percentual", String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", space.freePercent()));
        p.put("livre", space.freeBytes());
        p.put("total", space.totalBytes());
        return notifications.notifyOnce(ownerId, "LOW_SPACE", NotificationService.WARN, p, "armazenamento", 24);
    }

    public void checkAll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Storage s : storages()) {
                Measurement m = measure(s, false);
                m.space().ifPresent(sp -> evaluate(s.ownerId(), m.machineName(), s.dir(), sp));
            }
        } catch (RuntimeException e) {
            log.warn("Conferência de espaço falhou: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    public void checkAsync() {
        Thread.ofVirtual().name("m2m-space-check").start(this::checkAll);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "America/Sao_Paulo")
    void daily() {
        checkAll();
    }

}
