package br.com.vovolinux.m2m.transfer;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Itens em transferência neste momento: permitem cancelar de fora e medir a velocidade. */
@Component
public class RunningTransfers {

    /** Estado vivo de um item: bandeira de cancelamento, fluxos abertos e contagem de bytes. */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Closeable in;
        private volatile Closeable out;
        volatile long bytes;
        final long startNanos = System.nanoTime();
        final long startBytes;

        Handle(long startBytes) {
            this.startBytes = startBytes;
            this.bytes = startBytes;
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        void streams(Closeable in, Closeable out) {
            this.in = in;
            this.out = out;
        }

        /** Marca o cancelamento e fecha os fluxos para destravar leituras e gravações bloqueadas na rede. */
        void cancel() {
            cancelled.set(true);
            close(in);
            close(out);
        }

        private static void close(Closeable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignorado) {
                    // já fechado ou conexão caída
                }
            }
        }

        public long bytesPerSecond() {
            double secs = (System.nanoTime() - startNanos) / 1e9;
            return secs < 0.25 ? 0 : (long) ((bytes - startBytes) / secs);
        }
    }

    private final Map<Long, Handle> running = new ConcurrentHashMap<>();

    Handle register(long itemId, long startBytes) {
        Handle h = new Handle(startBytes);
        running.put(itemId, h);
        return h;
    }

    void unregister(long itemId) {
        running.remove(itemId);
    }

    public boolean cancel(long itemId) {
        Handle h = running.get(itemId);
        if (h == null) {
            return false;
        }
        h.cancel();
        return true;
    }

    public void cancelAll() {
        running.values().forEach(Handle::cancel);
    }

    public Handle get(long itemId) {
        return running.get(itemId);
    }
}
