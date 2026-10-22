package br.com.vovolinux.m2m.job;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica no RabbitMQ o que está pendente em {@code job_outbox}. Se o broker estiver fora, tenta de novo no próximo
 * ciclo; mensagens duplicadas são inofensivas porque o consumidor só executa jobs ainda QUEUED.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JobRepository repository;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;

    public OutboxPublisher(JobRepository repository, RabbitTemplate rabbit, PlatformTransactionManager tm) {
        this.repository = repository;
        this.rabbit = rabbit;
        // Transação própria: o "kick" roda logo após o commit de quem criou o job.
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Pedido para publicar já (depois do commit); o ciclo agendado cobre qualquer falha. */
    public void kick() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.debug("Publicação imediata adiada: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void scheduled() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.warn("Falha ao publicar a fila de jobs (nova tentativa em 1 s): {}", e.getMessage());
        }
    }

    public synchronized void publishPending() {
        tx.executeWithoutResult(status -> {
            List<JobRepository.OutboxEntry> pendentes = repository.pendingOutbox(100);
            for (JobRepository.OutboxEntry e : pendentes) {
                try {
                    rabbit.convertAndSend(e.exchange(), e.routingKey(), String.valueOf(e.refId()));
                } catch (AmqpException ex) {
                    throw new IllegalStateException("broker indisponível", ex);
                }
                repository.markPublished(e.id());
            }
        });
    }
}
