package br.com.vovolinux.m2m.notification;

import br.com.vovolinux.m2m.job.ProgressBus;
import br.com.vovolinux.m2m.mail.EmailService;
import br.com.vovolinux.m2m.user.UserRepository;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notificações do m2m: sempre no painel (com tempo real por SSE) e por e-mail, pelo SMTP do usuário dono, quando ele
 * tiver o envio ativo. Falha de e-mail nunca afeta o job; fica registrada na notificação (D27).
 */
@Service
public class NotificationService {

    public static final String INFO = "INFO";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final EmailService email;
    private final UserRepository users;
    private final ProgressBus bus;
    private final MessageSource messages;
    private final JsonMapper json;

    public NotificationService(NotificationRepository repository, EmailService email, UserRepository users,
            ProgressBus bus, MessageSource messages, JsonMapper json) {
        this.repository = repository;
        this.email = email;
        this.users = users;
        this.bus = bus;
        this.messages = messages;
        this.json = json;
    }

    public long notify(long ownerId, String type, String severity, Map<String, Object> params, String targetType,
            Long targetId) {
        long id;
        try {
            id = repository.insert(ownerId, type, severity, "notificacao." + type, json.writeValueAsString(params),
                    targetType, targetId);
        } catch (RuntimeException e) {
            log.error("Não foi possível gravar a notificação {}", type, e);
            return -1;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("type", "notification");
        event.put("id", id);
        event.put("notificationType", type);
        event.put("severity", severity);
        bus.publish("notification." + ownerId, event);
        sendEmail(ownerId, id, type, params);
        return id;
    }

    /** Notificação do sistema (ex.: falha do backup do banco): vai para todos os administradores. */
    public void notifyAdmins(String type, String severity, Map<String, Object> params) {
        for (long adminId : repository.adminIds()) {
            notify(adminId, type, severity, params, null, null);
        }
    }

    /** Só notifica se não houve outra igual (mesma chave em params) nas últimas {@code hours} horas. */
    public boolean notifyOnce(long ownerId, String type, String severity, Map<String, Object> params, String dedupKey,
            int hours) {
        Object v = params.get(dedupKey);
        if (v != null && repository.existsRecent(ownerId, type, dedupKey, String.valueOf(v), hours)) {
            return false;
        }
        notify(ownerId, type, severity, params, null, null);
        return true;
    }

    private void sendEmail(long ownerId, long notificationId, String type, Map<String, Object> params) {
        var setting = email.activeSetting(ownerId);
        if (setting.isEmpty()) {
            return; // sem SMTP ou envio desativado: só o painel
        }
        var user = users.findById(ownerId);
        if (user.isEmpty()) {
            return;
        }
        String subject = "[m2m] " + render("notificacao." + type + ".assunto", params);
        String body = render("notificacao." + type, params) + "\n\n" + render("email.rodape", Map.of());
        email.sendAsync(ownerId, user.get().email(), subject, body, result -> {
            if (result.startsWith("FAILED:")) {
                repository.setEmailStatus(notificationId, "FAILED", result.substring(7));
            } else {
                repository.setEmailStatus(notificationId, result, null);
            }
        });
    }

    /** Texto do e-mail: o modelo em messages_pt_BR.properties com {chave} trocada pelos parâmetros. */
    String render(String code, Map<String, Object> params) {
        String t = messages.getMessage(code, null, code, Locale.forLanguageTag("pt-BR"));
        for (var e : params.entrySet()) {
            t = t.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return t;
    }
}
