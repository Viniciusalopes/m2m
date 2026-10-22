package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.files.sftp.HostKeyPolicy;
import br.com.vovolinux.m2m.notification.NotificationService;
import br.com.vovolinux.m2m.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Impressão digital confirmada do servidor (chave SSH ou certificado TLS). A primeira conexão fica pendente até o
 * usuário confirmar; se a impressão mudar depois, a conexão é recusada até nova confirmação (D12).
 */
@Service
public class ServerIdentityService {

    public record Identity(String kind, String fingerprint, Instant confirmedAt, String pendingFingerprint) {

        public boolean confirmed() {
            return fingerprint != null && pendingFingerprint == null;
        }
    }

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<NotificationService> notifications;

    public ServerIdentityService(JdbcClient jdbc, AuditService audit,
            org.springframework.beans.factory.ObjectProvider<NotificationService> notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    public Optional<Identity> find(long machineId) {
        return jdbc.sql("""
                select kind, fingerprint, confirmed_at, pending_fingerprint from server_identity
                 where machine_id = :m""").param("m", machineId).query((rs, i) -> {
            Timestamp ts = rs.getTimestamp("confirmed_at");
            return new Identity(rs.getString("kind"), rs.getString("fingerprint"),
                    ts == null ? null : ts.toInstant(), rs.getString("pending_fingerprint"));
        }).optional();
    }

    /** Política usada na conexão: aceita só a impressão confirmada; do contrário registra a vista e recusa. */
    public HostKeyPolicy policy(long machineId, String kind) {
        return fingerprint -> {
            Optional<Identity> id = find(machineId);
            String confirmada = id.map(Identity::fingerprint).orElse(null);
            if (confirmada != null && confirmada.equals(fingerprint)) {
                if (id.get().pendingFingerprint() != null) {
                    clearPending(machineId);
                }
                return;
            }
            registerPending(machineId, kind, fingerprint);
            if (confirmada != null) {
                notifyChanged(machineId, fingerprint);
            }
            throw new IdentityException(
                    confirmada == null ? IdentityException.CONFIRMACAO_NECESSARIA : IdentityException.ALTERADA,
                    fingerprint, confirmada);
        };
    }

    /** A identidade mudou: avisa o dono, no máximo uma vez por hora para a mesma impressão digital. */
    private void notifyChanged(long machineId, String fingerprint) {
        try {
            var row = jdbc.sql("select owner_id, name from machine where id = :m").param("m", machineId)
                    .query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional();
            NotificationService n = notifications.getIfAvailable();
            if (row.isPresent() && n != null) {
                n.notifyOnce((Long) row.get()[0], "IDENTITY_CHANGED", NotificationService.ERROR,
                        Map.of("maquina", row.get()[1], "fingerprint", fingerprint), "fingerprint", 1);
            }
        } catch (RuntimeException e) {
            // notificação é acessória: a conexão já está sendo recusada
        }
    }

    private void registerPending(long machineId, String kind, String fingerprint) {
        jdbc.sql("""
                insert into server_identity (machine_id, kind, pending_fingerprint, pending_seen_at)
                values (:m, :k, :f, now())
                on conflict (machine_id) do update set pending_fingerprint = excluded.pending_fingerprint,
                       pending_seen_at = now()""").param("m", machineId).param("k", kind).param("f", fingerprint)
                .update();
    }

    private void clearPending(long machineId) {
        jdbc.sql("update server_identity set pending_fingerprint = null, pending_seen_at = null where machine_id = :m")
                .param("m", machineId).update();
    }

    /** O usuário confirma a impressão vista; só vale se for exatamente a que está pendente. */
    public void confirm(long machineId, String machineName, String fingerprint) {
        Identity id = find(machineId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "identidade.nada-pendente"));
        if (id.pendingFingerprint() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "identidade.nada-pendente");
        }
        if (!id.pendingFingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "identidade.fingerprint-diferente");
        }
        jdbc.sql("""
                update server_identity set fingerprint = pending_fingerprint, confirmed_at = now(),
                       pending_fingerprint = null, pending_seen_at = null where machine_id = :m""")
                .param("m", machineId).update();
        audit.record("SERVER_IDENTITY_CONFIRM", machineName, AuditService.SUCESSO,
                Map.of("fingerprint", fingerprint, "anterior", String.valueOf(id.fingerprint())));
    }

    /** Descarta a identidade (ex.: host/porta da máquina mudou): a próxima conexão exigirá nova confirmação. */
    public void forget(long machineId) {
        jdbc.sql("delete from server_identity where machine_id = :m").param("m", machineId).update();
    }
}
