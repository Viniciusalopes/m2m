package br.com.vovolinux.m2m.files;

import br.com.vovolinux.m2m.machine.Machine;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import br.com.vovolinux.m2m.files.sftp.SftpFileGateway;

/**
 * Pool de conexões por máquina (padrão de 4 — D2): reaproveita conexões, reconecta quando uma cai e fecha tudo à
 * força para o cancelamento de emergência (D10). A máquina LOCAL não usa pool: abrir é gratuito.
 */
@Component
public class GatewayPool {

    static final Duration IDLE_MAX = Duration.ofMinutes(5);
    static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(60);

    /** Conexão emprestada; {@link #close()} devolve ao pool, {@link #discard()} descarta (após erro de conexão). */
    public final class Lease implements AutoCloseable {
        private final MachinePool pool;
        private final PooledGateway pooled;
        private boolean done;

        private Lease(MachinePool pool, PooledGateway pooled) {
            this.pool = pool;
            this.pooled = pooled;
        }

        public FileGateway gateway() {
            return pooled.gateway;
        }

        public void discard() {
            finish(false);
        }

        @Override
        public void close() {
            finish(true);
        }

        private synchronized void finish(boolean reusable) {
            if (done) {
                return;
            }
            done = true;
            pool.release(pooled, reusable);
        }
    }

    private static final class PooledGateway {
        final FileGateway gateway;
        final long generation;
        Instant idleSince = Instant.now();

        PooledGateway(FileGateway gateway, long generation) {
            this.gateway = gateway;
            this.generation = generation;
        }

        boolean alive() {
            if (gateway instanceof SftpFileGateway s) {
                ClientSession cs = s.session();
                return cs.isOpen() && !cs.isClosing();
            }
            return gateway.isOpen();
        }
    }

    private final class MachinePool {
        final Semaphore permits;
        final Deque<PooledGateway> idle = new ArrayDeque<>();
        final Set<PooledGateway> all = ConcurrentHashMap.newKeySet();
        volatile long generation;

        MachinePool(int max) {
            this.permits = new Semaphore(max, true);
        }

        void release(PooledGateway g, boolean reusable) {
            try {
                synchronized (this) {
                    if (reusable && g.generation == generation && g.alive()) {
                        g.idleSince = Instant.now();
                        idle.push(g);
                        return;
                    }
                }
                all.remove(g);
                g.gateway.close();
            } finally {
                permits.release();
            }
        }

        synchronized PooledGateway pollIdle() {
            while (!idle.isEmpty()) {
                PooledGateway g = idle.pop();
                if (g.generation == generation && g.alive()) {
                    return g;
                }
                all.remove(g);
                g.gateway.close();
            }
            return null;
        }
    }

    private final GatewayFactory factory;
    private final Map<Long, MachinePool> pools = new ConcurrentHashMap<>();

    public GatewayPool(GatewayFactory factory) {
        this.factory = factory;
    }

    /** Empresta uma conexão; bloqueia até haver vaga (limite por máquina). */
    public Lease acquire(Machine machine) throws IOException {
        MachinePool pool = pools.computeIfAbsent(machine.id(), id -> new MachinePool(machine.maxConnections()));
        try {
            if (!pool.permits.tryAcquire(ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new GatewayException("conexao.sem-vaga", "Todas as conexões com a máquina estão em uso");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido ao aguardar conexão", e);
        }
        try {
            PooledGateway g = pool.pollIdle();
            if (g == null) {
                g = new PooledGateway(factory.open(machine), pool.generation);
                pool.all.add(g);
            }
            return new Lease(pool, g);
        } catch (IOException | RuntimeException e) {
            pool.permits.release();
            throw e;
        }
    }

    /** Cancelamento de emergência: fecha à força todas as conexões da máquina, inclusive as em uso. */
    public void abortAll(long machineId) {
        MachinePool pool = pools.get(machineId);
        if (pool == null) {
            return;
        }
        synchronized (pool) {
            pool.generation++;
            pool.idle.clear();
        }
        for (PooledGateway g : pool.all) {
            g.gateway.close();
        }
        pool.all.clear();
    }

    /** A máquina foi editada: descarta conexões abertas com os dados antigos. */
    public void invalidate(long machineId) {
        abortAll(machineId);
        pools.remove(machineId);
    }

    @Scheduled(fixedDelay = 60_000)
    void closeIdle() {
        Instant limite = Instant.now().minus(IDLE_MAX);
        for (MachinePool pool : pools.values()) {
            synchronized (pool) {
                pool.idle.removeIf(g -> {
                    if (g.idleSince.isBefore(limite)) {
                        pool.all.remove(g);
                        g.gateway.close();
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    @PreDestroy
    void shutdown() {
        pools.keySet().forEach(this::abortAll);
    }
}
