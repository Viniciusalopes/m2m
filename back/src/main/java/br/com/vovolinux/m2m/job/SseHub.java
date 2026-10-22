package br.com.vovolinux.m2m.job;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Repassa ao navegador (Server-Sent Events) os eventos do exchange {@code m2m.progress}. Cada assinante escolhe uma
 * chave de roteamento exata (ex.: {@code job.42}) ou um prefixo terminado em {@code .#}.
 */
@Component
public class SseHub {

    private record Subscription(String pattern, SseEmitter emitter) {
        boolean matches(String key) {
            return pattern.endsWith(".#") ? key.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(key);
        }
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<SseEmitter, Subscription> byEmitter = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String pattern) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription s = new Subscription(pattern, emitter);
        subscriptions.add(s);
        byEmitter.put(emitter, s);
        Runnable remove = () -> {
            subscriptions.remove(s);
            byEmitter.remove(emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** Envio direto (ex.: o estado atual ao assinar). */
    public void send(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    @RabbitListener(queues = "#{@progressQueue.name}")
    void relay(Message message) {
        String key = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        for (Subscription s : subscriptions) {
            if (s.matches(key)) {
                send(s.emitter(), "progress", body);
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        for (Subscription s : subscriptions) {
            try {
                s.emitter().send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                s.emitter().completeWithError(e);
            }
        }
    }

    public int subscribers() {
        return subscriptions.size();
    }
}
