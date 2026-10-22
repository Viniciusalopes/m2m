package br.com.vovolinux.m2m.job;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Publica eventos de progresso no exchange topic {@code m2m.progress}. */
@Component
public class ProgressBus {

    private static final Logger log = LoggerFactory.getLogger(ProgressBus.class);

    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public ProgressBus(RabbitTemplate rabbit, JsonMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public void publish(String routingKey, Map<String, Object> event) {
        try {
            rabbit.convertAndSend(RabbitTopology.PROGRESS, routingKey, json.writeValueAsString(event));
        } catch (AmqpException e) {
            // Progresso é efêmero: sem o broker o job continua e o estado persiste no banco.
            log.debug("Evento de progresso descartado ({}): {}", routingKey, e.getMessage());
        }
    }
}
