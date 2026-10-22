package br.com.vovolinux.m2m.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remove diariamente os eventos de auditoria com mais de 365 dias. */
@Component
public class AuditRetentionJob {

    static final int RETENCAO_DIAS = 365;
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final AuditRepository repository;

    public AuditRetentionJob(AuditRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void limpar() {
        int removidos = repository.deleteOlderThanDays(RETENCAO_DIAS);
        if (removidos > 0) {
            log.info("Auditoria: {} evento(s) com mais de {} dias removido(s)", removidos, RETENCAO_DIAS);
        }
    }
}
