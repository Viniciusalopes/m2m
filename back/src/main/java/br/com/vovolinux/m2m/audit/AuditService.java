package br.com.vovolinux.m2m.audit;

import br.com.vovolinux.m2m.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registra eventos de auditoria (somente inclusão). O chamador nunca deve passar senhas, chaves ou
 * conteúdo de arquivos em {@code detalhes}: a regra é cumprida por quem chama.
 */
@Service
public class AuditService {

    public static final String SUCESSO = "SUCCESS";
    public static final String FALHA = "FAILURE";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;
    private final CurrentUser currentUser;
    private final JsonMapper json;

    public AuditService(AuditRepository repository, CurrentUser currentUser, JsonMapper json) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.json = json;
    }

    /** Usa o usuário autenticado e o IP da requisição atual. */
    public void record(String acao, String alvo, String resultado, Map<String, Object> detalhes) {
        Optional<CurrentUser.Info> user = currentUser.optional();
        record(user.map(CurrentUser.Info::id).orElse(null), user.map(CurrentUser.Info::username).orElse(null),
                currentIp(), acao, alvo, resultado, detalhes);
    }

    public void record(String acao, String alvo, String resultado) {
        record(acao, alvo, resultado, Map.of());
    }

    /** Para eventos sem usuário autenticado (ex.: falha de login). */
    public void record(Long ownerId, String username, String ip, String acao, String alvo, String resultado,
            Map<String, Object> detalhes) {
        try {
            repository.insert(ownerId, username, ip, acao, alvo, resultado, json.writeValueAsString(detalhes));
        } catch (RuntimeException e) {
            // A auditoria não pode derrubar a operação em curso, mas a falha precisa ficar visível no log.
            log.error("Falha ao gravar evento de auditoria {} ({})", acao, alvo, e);
        }
    }

    public static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
