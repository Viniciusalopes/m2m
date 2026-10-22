package br.com.vovolinux.m2m.sync;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncScheduleRepository {

    public record Row(long id, long profileId, long destMachineId, LocalTime time, boolean enabled,
            int maxDeletionsPercent, int maxDeletionsAbs) {
    }

    private final JdbcClient jdbc;

    public SyncScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Row map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getLong("profile_id"), rs.getLong("dest_machine_id"),
                rs.getTime("time_of_day").toLocalTime(), rs.getBoolean("enabled"), rs.getInt("max_deletions_percent"),
                rs.getInt("max_deletions_abs"));
    }

    public List<Row> byProfile(long profileId) {
        return jdbc.sql("select * from sync_schedule where profile_id = :p order by id").param("p", profileId)
                .query(SyncScheduleRepository::map).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("select * from sync_schedule where id = :id").param("id", id)
                .query(SyncScheduleRepository::map).optional();
    }

    public Optional<Row> findByProfileAndDest(long profileId, long destId) {
        return jdbc.sql("select * from sync_schedule where profile_id = :p and dest_machine_id = :d").param("p", profileId)
                .param("d", destId).query(SyncScheduleRepository::map).optional();
    }

    public List<Row> enabledAll() {
        return jdbc.sql("select * from sync_schedule where enabled").query(SyncScheduleRepository::map).list();
    }

    /** Agendamentos de outros perfis (qualquer estado), com o dono, para checar sentidos opostos. */
    public List<Row> allExcept(long profileId) {
        return jdbc.sql("select * from sync_schedule where profile_id <> :p").param("p", profileId)
                .query(SyncScheduleRepository::map).list();
    }

    public long insert(long profileId, long destId, LocalTime time, boolean enabled, int pct, int abs) {
        return jdbc.sql("""
                insert into sync_schedule (profile_id, dest_machine_id, time_of_day, enabled, max_deletions_percent,
                       max_deletions_abs) values (:p, :d, :t, :e, :pc, :a) returning id""")
                .param("p", profileId).param("d", destId).param("t", Time.valueOf(time)).param("e", enabled)
                .param("pc", pct).param("a", abs).query(Long.class).single();
    }

    public void update(long id, LocalTime time, boolean enabled, int pct, int abs) {
        jdbc.sql("""
                update sync_schedule set time_of_day = :t, enabled = :e, max_deletions_percent = :pc,
                       max_deletions_abs = :a where id = :id""")
                .param("t", Time.valueOf(time)).param("e", enabled).param("pc", pct).param("a", abs).param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("delete from sync_schedule where id = :id").param("id", id).update();
    }
}
