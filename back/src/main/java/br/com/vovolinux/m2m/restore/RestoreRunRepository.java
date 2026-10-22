package br.com.vovolinux.m2m.restore;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RestoreRunRepository {

    public record Row(long id, Long jobId, Long archiveId, String archiveName, Long destMachineId,
            String destMachineName, boolean selective, String paths, String status, String counts,
            Long preArchiveId, Long undoOfId, Instant createdAt, Instant finishedAt) {
    }

    private final JdbcClient jdbc;

    public RestoreRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long ownerId, Long jobId, Long archiveId, String archiveName, Long destMachineId,
            String destMachineName, boolean selective, String pathsJson) {
        return jdbc.sql("""
                insert into restore_run (owner_id, job_id, archive_id, archive_name, dest_machine_id,
                       dest_machine_name, selective, paths, status)
                values (:o, :j, :a, :an, :dm, :dn, :s, cast(:p as jsonb), 'RUNNING') returning id""")
                .param("o", ownerId).param("j", jobId).param("a", archiveId).param("an", archiveName)
                .param("dm", destMachineId).param("dn", destMachineName).param("s", selective).param("p", pathsJson)
                .query(Long.class).single();
    }

    public void finish(long id, String status, String countsJson, Long preArchiveId) {
        jdbc.sql("""
                update restore_run set status = :s, counts = cast(:c as jsonb), pre_restore_archive_id = :pre,
                       finished_at = now() where id = :id""")
                .param("s", status).param("c", countsJson).param("pre", preArchiveId).param("id", id).update();
    }

    public void setUndoOf(long id, long undoOfId) {
        jdbc.sql("update restore_run set undo_of_id = :u where id = :id").param("u", undoOfId).param("id", id).update();
    }

    /** Recuperação após queda: execuções que ficaram RUNNING viram INTERRUPTED. */
    public void markInterruptedByJob(long jobId) {
        jdbc.sql("update restore_run set status = 'INTERRUPTED', finished_at = now() where job_id = :j and status = 'RUNNING'")
                .param("j", jobId).update();
    }

    public List<Row> list(long ownerId, int limit, int offset) {
        return jdbc.sql("""
                select id, job_id, archive_id, archive_name, dest_machine_id, dest_machine_name, selective,
                       paths::text as paths, status, counts::text as counts, pre_restore_archive_id, undo_of_id,
                       created_at, finished_at
                  from restore_run where owner_id = :o order by id desc limit :l offset :off""")
                .param("o", ownerId).param("l", limit).param("off", offset)
                .query((rs, i) -> {
                    Timestamp fin = rs.getTimestamp("finished_at");
                    return new Row(rs.getLong("id"), (Long) rs.getObject("job_id"), (Long) rs.getObject("archive_id"),
                            rs.getString("archive_name"), (Long) rs.getObject("dest_machine_id"),
                            rs.getString("dest_machine_name"), rs.getBoolean("selective"), rs.getString("paths"),
                            rs.getString("status"), rs.getString("counts"),
                            (Long) rs.getObject("pre_restore_archive_id"), (Long) rs.getObject("undo_of_id"),
                            rs.getTimestamp("created_at").toInstant(), fin == null ? null : fin.toInstant());
                }).list();
    }
}
