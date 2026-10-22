package br.com.vovolinux.m2m.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private static final String COLUMNS = """
            id, owner_id, type, status, step, progress::text as progress, trigger, profile_id, profile_name,
            params::text as params, result::text as result, created_at, started_at, finished_at""";

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Job map(ResultSet rs, int i) throws SQLException {
        return new Job(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("type"), rs.getString("status"),
                rs.getString("step"), rs.getString("progress"), rs.getString("trigger"),
                (Long) rs.getObject("profile_id"), rs.getString("profile_name"), rs.getString("params"),
                rs.getString("result"), rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Serializa a criação de jobs: um job ativo por vez é decidido dentro desta trava. */
    public void lockCreation() {
        jdbc.sql("select pg_advisory_xact_lock(7001)").query().singleRow();
    }

    public long insert(long ownerId, String type, String trigger, Long profileId, String profileName, String paramsJson) {
        return jdbc.sql("""
                insert into job (owner_id, type, status, trigger, profile_id, profile_name, params)
                values (:o, :t, 'QUEUED', :tr, :p, :pn, cast(:params as jsonb)) returning id""")
                .param("o", ownerId).param("t", type).param("tr", trigger).param("p", profileId)
                .param("pn", profileName).param("params", paramsJson).query(Long.class).single();
    }

    public Optional<Job> find(long ownerId, long id) {
        return jdbc.sql("select " + COLUMNS + " from job where owner_id = :o and id = :id").param("o", ownerId)
                .param("id", id).query(JobRepository::map).optional();
    }

    /** Sem filtro de dono: uso interno do executor. */
    public Optional<Job> findAny(long id) {
        return jdbc.sql("select " + COLUMNS + " from job where id = :id").param("id", id).query(JobRepository::map)
                .optional();
    }

    public List<Job> active() {
        return jdbc.sql("select " + COLUMNS + " from job where status in ('QUEUED', 'RUNNING') order by id")
                .query(JobRepository::map).list();
    }

    public List<Job> activeOfProfile(long profileId) {
        return jdbc.sql("select " + COLUMNS
                + " from job where profile_id = :p and status in ('QUEUED', 'RUNNING') order by id")
                .param("p", profileId).query(JobRepository::map).list();
    }

    public boolean queuedExists(String type, String trigger, Long profileId) {
        return jdbc.sql("""
                select count(*) from job where status = 'QUEUED' and type = :t and trigger = :tr
                   and profile_id is not distinct from cast(:p as bigint)""")
                .param("t", type).param("tr", trigger).param("p", profileId).query(Long.class).single() > 0;
    }

    public List<Job> history(long ownerId, int limit, int offset) {
        return jdbc.sql("select " + COLUMNS + " from job where owner_id = :o order by id desc limit :l offset :off")
                .param("o", ownerId).param("l", limit).param("off", offset).query(JobRepository::map).list();
    }

    public long countHistory(long ownerId) {
        return jdbc.sql("select count(*) from job where owner_id = :o").param("o", ownerId).query(Long.class).single();
    }

    /** Reivindica o job para execução: só um consumidor consegue passar de QUEUED a RUNNING. */
    public Optional<Job> claim(long id) {
        return jdbc.sql("""
                update job set status = 'RUNNING', started_at = now() where id = :id and status = 'QUEUED'
                returning """ + " " + COLUMNS).param("id", id).query(JobRepository::map).optional();
    }

    public void saveProgress(long id, String step, String progressJson) {
        jdbc.sql("update job set step = :s, progress = cast(:p as jsonb) where id = :id")
                .param("s", step).param("p", progressJson).param("id", id).update();
    }

    public void finish(long id, String status, String progressJson, String resultJson) {
        jdbc.sql("""
                update job set status = :st, finished_at = now(), progress = cast(:p as jsonb),
                       result = cast(:r as jsonb) where id = :id""")
                .param("st", status).param("p", progressJson).param("r", resultJson).param("id", id).update();
    }

    /** Cancela um job que ainda está na fila. */
    public boolean cancelQueued(long id) {
        return jdbc.sql("""
                update job set status = 'CANCELED', finished_at = now() where id = :id and status = 'QUEUED'""")
                .param("id", id).update() > 0;
    }

    public List<Job> markRunningInterrupted() {
        return jdbc.sql("""
                update job set status = 'INTERRUPTED', finished_at = now(),
                       result = jsonb_build_object('reason', 'RESTART')
                 where status = 'RUNNING' returning """ + " " + COLUMNS).query(JobRepository::map).list();
    }

    public List<Job> queuedWithoutPendingPublication() {
        return jdbc.sql("select " + COLUMNS + """
                 \sfrom job j where j.status = 'QUEUED'
                   and not exists (select 1 from job_outbox o where o.kind = 'JOB' and o.ref_id = j.id
                                      and o.published_at is null) order by j.id""")
                .query(JobRepository::map).list();
    }

    // ---- mensagens ----

    public void addMessage(long jobId, String level, String code, String paramsJson) {
        jdbc.sql("insert into job_message (job_id, level, code, params) values (:j, :l, :c, cast(:p as jsonb))")
                .param("j", jobId).param("l", level).param("c", code).param("p", paramsJson).update();
    }

    public record Message(long id, java.time.Instant at, String level, String code, String params) {
    }

    public List<Message> messages(long jobId) {
        return jdbc.sql("select id, at, level, code, params::text as params from job_message where job_id = :j order by id")
                .param("j", jobId).query((rs, i) -> new Message(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                        rs.getString(3), rs.getString(4), rs.getString(5))).list();
    }

    // ---- outbox ----

    public void insertOutbox(String kind, long refId, String exchange, String routingKey, String payloadJson) {
        jdbc.sql("""
                insert into job_outbox (kind, ref_id, exchange, routing_key, payload)
                values (:k, :r, :e, :rk, cast(:p as jsonb))""")
                .param("k", kind).param("r", refId).param("e", exchange).param("rk", routingKey)
                .param("p", payloadJson).update();
    }

    public record OutboxEntry(long id, String kind, long refId, String exchange, String routingKey) {
    }

    public List<OutboxEntry> pendingOutbox(int limit) {
        return jdbc.sql("""
                select id, kind, ref_id, exchange, routing_key from job_outbox
                 where published_at is null order by id limit :l for update skip locked""").param("l", limit)
                .query((rs, i) -> new OutboxEntry(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5))).list();
    }

    public void markPublished(long outboxId) {
        jdbc.sql("update job_outbox set published_at = now() where id = :id").param("id", outboxId).update();
    }
}
