package br.com.vovolinux.m2m.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncRunRepository {

    public record Row(long id, Long jobId, Long profileId, String profileName, Long destMachineId,
            String destMachineName, String trigger, String status, String plan, String counts, Long preArchiveId,
            Instant createdAt, Instant finishedAt) {
    }

    private final JdbcClient jdbc;

    public SyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long ownerId, long jobId, long profileId, String profileName, long destId, String destName,
            String trigger) {
        return jdbc.sql("""
                insert into sync_run (owner_id, job_id, profile_id, profile_name, dest_machine_id, dest_machine_name,
                       trigger, status) values (:o, :j, :p, :pn, :d, :dn, :t, 'RUNNING') returning id""")
                .param("o", ownerId).param("j", jobId).param("p", profileId).param("pn", profileName)
                .param("d", destId).param("dn", destName).param("t", trigger).query(Long.class).single();
    }

    public void finish(long id, String status, String planJson, String countsJson, Long preArchiveId) {
        jdbc.sql("""
                update sync_run set status = :s, plan = cast(:pl as jsonb), counts = cast(:c as jsonb),
                       pre_sync_archive_id = :pre, finished_at = now() where id = :id""")
                .param("s", status).param("pl", planJson).param("c", countsJson).param("pre", preArchiveId)
                .param("id", id).update();
    }

    public void markUndone(long preArchiveId) {
        jdbc.sql("update sync_run set status = 'UNDONE' where pre_sync_archive_id = :a").param("a", preArchiveId).update();
    }

    public void markInterruptedByJob(long jobId) {
        jdbc.sql("update sync_run set status = 'INTERRUPTED', finished_at = now() where job_id = :j and status = 'RUNNING'")
                .param("j", jobId).update();
    }

    /** Existe sincronização manual concluída para o perfil e o destino? (condição para ativar o agendamento) */
    public boolean hasCompletedManual(long profileId, long destMachineId) {
        return jdbc.sql("""
                select count(*) from sync_run where profile_id = :p and dest_machine_id = :d and trigger = 'MANUAL'
                   and status in ('COMPLETED', 'COMPLETED_WITH_WARNINGS')""").param("p", profileId)
                .param("d", destMachineId).query(Long.class).single() > 0;
    }

    public List<Row> list(long ownerId, Long profileId, int limit, int offset) {
        return jdbc.sql("""
                select id, job_id, profile_id, profile_name, dest_machine_id, dest_machine_name, trigger, status,
                       plan::text as plan, counts::text as counts, pre_sync_archive_id, created_at, finished_at
                  from sync_run where owner_id = :o and (cast(:p as bigint) is null or profile_id = :p)
                 order by id desc limit :l offset :off""")
                .param("o", ownerId).param("p", profileId).param("l", limit).param("off", offset)
                .query((rs, i) -> {
                    Timestamp fin = rs.getTimestamp("finished_at");
                    return new Row(rs.getLong("id"), (Long) rs.getObject("job_id"), (Long) rs.getObject("profile_id"),
                            rs.getString("profile_name"), (Long) rs.getObject("dest_machine_id"),
                            rs.getString("dest_machine_name"), rs.getString("trigger"), rs.getString("status"),
                            rs.getString("plan"), rs.getString("counts"), (Long) rs.getObject("pre_sync_archive_id"),
                            rs.getTimestamp("created_at").toInstant(), fin == null ? null : fin.toInstant());
                }).list();
    }

}
