package br.com.vovolinux.m2m.job;

import java.util.Set;

/** Estados de um job. */
public final class JobStatus {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS";
    public static final String FAILED = "FAILED";
    /** Emergência do usuário ou queda da aplicação durante a execução. */
    public static final String INTERRUPTED = "INTERRUPTED";
    /** Cancelado ainda na fila. */
    public static final String CANCELED = "CANCELED";
    /** Sincronização agendada parada por proteção, aguardando o usuário (D24). */
    public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";

    public static final Set<String> ACTIVE = Set.of(QUEUED, RUNNING);

    private JobStatus() {
    }

    public static boolean isFinal(String status) {
        return !ACTIVE.contains(status);
    }
}
