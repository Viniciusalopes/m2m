package br.com.vovolinux.m2m.job;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topologia do RabbitMQ (D9, D16); também declarada em queue/definitions.json — a declaração é idempotente. */
@Configuration
public class RabbitTopology {

    public static final String JOBS_EXCHANGE = "m2m.jobs";
    public static final String JOBS_RUN = "m2m.jobs.run";
    public static final String TRANSFERS = "m2m.transfers";
    public static final String PROGRESS = "m2m.progress";
    public static final String RUN_KEY = "run";

    @Bean
    Declarables m2mTopology() {
        DirectExchange jobs = new DirectExchange(JOBS_EXCHANGE, true, false);
        Queue jobsRun = new Queue(JOBS_RUN, true);
        DirectExchange transfers = new DirectExchange(TRANSFERS, true, false);
        Queue transferQueue = new Queue(TRANSFERS, true);
        TopicExchange progress = new TopicExchange(PROGRESS, true, false);
        return new Declarables(jobs, jobsRun, BindingBuilder.bind(jobsRun).to(jobs).with(RUN_KEY), transfers,
                transferQueue, BindingBuilder.bind(transferQueue).to(transfers).with(RUN_KEY), progress);
    }

    /** Fila exclusiva desta instância para repassar o progresso ao navegador (SSE). */
    @Bean
    AnonymousQueue progressQueue() {
        return new AnonymousQueue();
    }

    @Bean
    Declarables progressBindings(AnonymousQueue progressQueue) {
        TopicExchange progress = new TopicExchange(PROGRESS, true, false);
        return new Declarables(
                BindingBuilder.bind(progressQueue).to(progress).with("job.#"),
                BindingBuilder.bind(progressQueue).to(progress).with("transfer.#"),
                BindingBuilder.bind(progressQueue).to(progress).with("notification.#"));
    }

}
