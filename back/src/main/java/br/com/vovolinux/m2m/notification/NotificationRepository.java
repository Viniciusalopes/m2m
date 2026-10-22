package br.com.vovolinux.m2m.notification;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    public record Row(long id, long ownerId, String type, String severity, String messageCode, String params,
            String targetType, Long targetId, boolean read, String emailStatus, String emailError, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, owner_id, type, severity, message_code, params::text as params, target_type, target_id, read,
            email_status, email_error, created_at\s""";

    public long insert(long ownerId, String type, String severity, String messageCode, String paramsJson,
            String targetType, Long targetId) {
        return jdbc.sql("""
                insert into notification (owner_id, type, severity, message_code, params, target_type, target_id)
                values (:o, :t, :s, :m, cast(:p as jsonb), :tt, :ti) returning id""")
                .param("o", ownerId).param("t", type).param("s", severity).param("m", messageCode)
                .param("p", paramsJson).param("tt", targetType).param("ti", targetId).query(Long.class).single();
    }

    public List<Row> list(long ownerId, boolean unreadOnly, int limit, int offset) {
        return jdbc.sql("select " + COLUMNS + " from notification where owner_id = :o"
                + (unreadOnly ? " and not read" : "") + " order by id desc limit :l offset :off")
                .param("o", ownerId).param("l", limit).param("off", offset).query((rs, i) -> new Row(rs.getLong("id"),
                        rs.getLong("owner_id"), rs.getString("type"), rs.getString("severity"),
                        rs.getString("message_code"), rs.getString("params"), rs.getString("target_type"),
                        (Long) rs.getObject("target_id"), rs.getBoolean("read"), rs.getString("email_status"),
                        rs.getString("email_error"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public long countUnread(long ownerId) {
        return jdbc.sql("select count(*) from notification where owner_id = :o and not read").param("o", ownerId)
                .query(Long.class).single();
    }

    public boolean markRead(long ownerId, long id) {
        return jdbc.sql("update notification set read = true where owner_id = :o and id = :id").param("o", ownerId)
                .param("id", id).update() > 0;
    }

    public int markAllRead(long ownerId) {
        return jdbc.sql("update notification set read = true where owner_id = :o and not read").param("o", ownerId)
                .update();
    }

    public void setEmailStatus(long id, String status, String error) {
        jdbc.sql("update notification set email_status = :s, email_error = :e where id = :id").param("s", status)
                .param("e", error).param("id", id).update();
    }

    /** Já houve notificação igual (tipo + chave em {@code params}) no período? Para limitar a 1 por dia, etc. */
    public boolean existsRecent(long ownerId, String type, String paramKey, String paramValue, int hours) {
        return jdbc.sql("""
                select count(*) from notification where owner_id = :o and type = :t
                   and params ->> :k = :v and created_at > now() - make_interval(hours => :h)""")
                .param("o", ownerId).param("t", type).param("k", paramKey).param("v", paramValue).param("h", hours)
                .query(Long.class).single() > 0;
    }

    public List<Long> adminIds() {
        return jdbc.sql("select id from app_user where role = 'ADMIN' and enabled").query(Long.class).list();
    }
}
