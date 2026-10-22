package br.com.vovolinux.m2m.job;

/** Executa um tipo de job. Exceções viram FAILED; cancelamento vira INTERRUPTED. */
public interface JobHandler {

    String type();

    void run(JobContext ctx) throws Exception;

    /**
     * Chamado na inicialização para cada job que estava RUNNING quando a aplicação caiu: remove arquivos parciais e
     * temporários que o job deixou.
     */
    default void recover(Job job) {
    }

    /** Máquinas cujas conexões devem ser fechadas à força no cancelamento (ids vindos dos parâmetros). */
    default java.util.Set<Long> machinesOf(Job job, tools.jackson.databind.JsonNode params) {
        return java.util.Set.of();
    }
}
