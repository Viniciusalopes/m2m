package br.com.vovolinux.m2m.mail;

import br.com.vovolinux.m2m.crypto.CryptoService;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envio de e-mail pelo SMTP do próprio usuário (D27): o {@code JavaMailSender} é montado na hora do envio a partir da
 * configuração salva (senha decifrada pela chave mestra). Nada de SMTP no ambiente.
 */
@Service
public class EmailService {

    static final int ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final SmtpSettingRepository settings;
    private final CryptoService crypto;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final long retryDelay;

    public EmailService(SmtpSettingRepository settings, CryptoService crypto,
            @Value("${m2m.mail.retry-delay-ms:2000}") long retryDelay) {
        this.settings = settings;
        this.crypto = crypto;
        this.retryDelay = retryDelay;
    }

    /** Dados efetivos de envio (senha em claro só aqui, em memória). */
    public record Effective(String host, int port, String security, String username, String password, String from) {
    }

    public Optional<SmtpSetting> activeSetting(long ownerId) {
        return settings.find(ownerId).filter(SmtpSetting::enabled);
    }

    Effective effective(SmtpSetting s) {
        String pw = s.passwordEnc() == null ? null : crypto.decryptString(s.passwordEnc());
        return new Effective(s.host(), s.port(), s.security(), s.username(), pw, s.fromAddress());
    }

    JavaMailSenderImpl sender(Effective e) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(e.host());
        s.setPort(e.port());
        if (e.username() != null && !e.username().isBlank()) {
            s.setUsername(e.username());
            s.setPassword(e.password());
        }
        s.setDefaultEncoding("UTF-8");
        Properties p = s.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(e.username() != null && !e.username().isBlank()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        switch (e.security()) {
            case "STARTTLS" -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case "SSL" -> p.put("mail.smtp.ssl.enable", "true");
            default -> {
            }
        }
        return s;
    }

    /** Envia uma vez e propaga a falha (usado pelo "e-mail de teste", que mostra o erro do servidor). */
    public void sendNow(Effective e, String to, String subject, String body) throws Exception {
        JavaMailSenderImpl sender = sender(e);
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
        h.setFrom(e.from());
        h.setTo(to);
        h.setSubject(subject);
        h.setText(body, false);
        sender.send(msg);
    }

    /** Envio assíncrono com 3 tentativas; o resultado final vai para o callback (SENT ou a mensagem de erro). */
    public void sendAsync(long ownerId, String to, String subject, String body, Consumer<String> result) {
        executor.submit(() -> {
            Optional<SmtpSetting> s = activeSetting(ownerId);
            if (s.isEmpty()) {
                result.accept("DISABLED");
                return;
            }
            Effective e = effective(s.get());
            String error = null;
            for (int i = 1; i <= ATTEMPTS; i++) {
                try {
                    sendNow(e, to, subject, body);
                    result.accept("SENT");
                    return;
                } catch (Exception ex) {
                    error = rootMessage(ex);
                    log.warn("E-mail para {} falhou (tentativa {}/{}): {}", to, i, ATTEMPTS, error);
                    if (i < ATTEMPTS) {
                        try {
                            Thread.sleep(retryDelayMillis() * i);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            result.accept("FAILED:" + error);
        });
    }

    long retryDelayMillis() {
        return retryDelay;
    }

    static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        String m = r.getMessage();
        if (t.getMessage() != null && (m == null || t.getMessage().length() > m.length())) {
            m = t.getMessage();
        }
        return m == null ? r.getClass().getSimpleName() : m;
    }
}
