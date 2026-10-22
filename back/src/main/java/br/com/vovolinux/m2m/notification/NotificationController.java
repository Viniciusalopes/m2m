package br.com.vovolinux.m2m.notification;

import br.com.vovolinux.m2m.job.SseHub;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record NotificationView(long id, String type, String severity, String messageCode, JsonNode params,
            String targetType, Long targetId, boolean read, String emailStatus, String emailError, Instant createdAt) {
    }

    public record Page(List<NotificationView> items, long unread) {
    }

    private final NotificationRepository repository;
    private final CurrentUser currentUser;
    private final SseHub hub;
    private final JsonMapper json;

    public NotificationController(NotificationRepository repository, CurrentUser currentUser, SseHub hub,
            JsonMapper json) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.hub = hub;
        this.json = json;
    }

    private long owner() {
        return currentUser.require().id();
    }

    static NotificationView view(NotificationRepository.Row r, JsonMapper json) {
        return new NotificationView(r.id(), r.type(), r.severity(), r.messageCode(), json.readTree(r.params()),
                r.targetType(), r.targetId(), r.read(), r.emailStatus(), r.emailError(), r.createdAt());
    }

    @GetMapping
    public Page list(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        long owner = owner();
        int limit = Math.min(Math.max(size, 1), 200);
        return new Page(repository.list(owner, unreadOnly, limit, Math.max(page, 0) * limit).stream()
                .map(r -> view(r, json)).toList(), repository.countUnread(owner));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", repository.countUnread(owner()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable long id) {
        if (!repository.markRead(owner(), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "notificacao.nao-encontrada");
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        repository.markAllRead(owner());
    }

    /** Notificações em tempo real (o sino do cabeçalho). */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return hub.subscribe("notification." + owner());
    }
}
