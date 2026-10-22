package br.com.vovolinux.m2m.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long ownerId, String username, String ip, String action, String target, String result,
            String detailsJson) {
        jdbc.sql("""
                insert into audit_event (owner_id, username, ip, action, target, result, details)
                values (:owner, :username, :ip, :action, :target, :result, cast(:details as jsonb))""")
                .param("owner", ownerId).param("username", username).param("ip", ip)
                .param("action", action).param("target", truncate(target, 500)).param("result", result)
                .param("details", detailsJson).update();
    }

    /** Eventos de um dono, do mais recente ao mais antigo; todos os filtros são opcionais. */
    public List<AuditEvent> search(long ownerId, Instant from, Instant to, String username, String action,
            int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                select id, occurred_at, username, ip, action, target, result, details::text as details
                  from audit_event where owner_id = :owner""");
        List<Object[]> params = new ArrayList<>();
        appendFilters(sql, params, from, to, username, action);
        sql.append(" order by occurred_at desc, id desc limit :limit offset :offset");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("owner", ownerId)
                .param("limit", limit).param("offset", offset);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query((rs, row) -> new AuditEvent(
                rs.getLong("id"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("username"),
                rs.getString("ip"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("result"),
                rs.getString("details"))).list();
    }

    public long count(long ownerId, Instant from, Instant to, String username, String action) {
        StringBuilder sql = new StringBuilder("select count(*) from audit_event where owner_id = :owner");
        List<Object[]> params = new ArrayList<>();
        appendFilters(sql, params, from, to, username, action);
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("owner", ownerId);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(Long.class).single();
    }

    private static void appendFilters(StringBuilder sql, List<Object[]> params, Instant from, Instant to,
            String username, String action) {
        if (from != null) {
            sql.append(" and occurred_at >= :from");
            params.add(new Object[] {"from", Timestamp.from(from)});
        }
        if (to != null) {
            sql.append(" and occurred_at < :to");
            params.add(new Object[] {"to", Timestamp.from(to)});
        }
        if (username != null && !username.isBlank()) {
            sql.append(" and username = :username");
            params.add(new Object[] {"username", username});
        }
        if (action != null && !action.isBlank()) {
            sql.append(" and action = :action");
            params.add(new Object[] {"action", action});
        }
    }

    /** Limpeza de retenção: o gatilho do banco só permite excluir eventos com mais de 365 dias. */
    public int deleteOlderThanDays(int days) {
        return jdbc.sql("delete from audit_event where occurred_at < now() - make_interval(days => :d)")
                .param("d", days).update();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
