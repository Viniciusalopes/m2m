package br.com.vovolinux.m2m.job;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sinal de cancelamento do job (emergência); o fechamento das conexões é feito junto (D10). */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("Job interrompido pelo usuário");
        }
    }
}
