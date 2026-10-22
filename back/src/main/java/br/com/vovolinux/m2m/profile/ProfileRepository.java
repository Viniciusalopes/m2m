package br.com.vovolinux.m2m.profile;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Todas as consultas filtram pelo dono (owner_id) — D21. */
@Repository
public class ProfileRepository {

    public record Row(
            long id,
            long ownerId,
            String name,
            String complement,
            long sourceMachineId,
            long storageMachineId,
            String storageDir,
            double suspectThreshold,
            boolean encryptionEnabled,
            Instant createdAt) {
    }

    public record MappingRow(long machineId, String path) {
    }

    public record DirRow(long id, int position, String path, List<MappingRow> mappings) {
    }

    public record ScheduleRow(LocalTime time, boolean enabled) {
    }

    private final JdbcClient jdbc;

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Row map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"), rs.getString("complement"),
                rs.getLong("source_machine_id"), rs.getLong("storage_machine_id"), rs.getString("storage_dir"),
                rs.getBigDecimal("suspect_threshold").doubleValue(), rs.getBoolean("encryption_enabled"),
                rs.getTimestamp("created_at").toInstant());
    }

    public List<Row> findAll(long ownerId) {
        return jdbc.sql("select * from profile where owner_id = :o order by lower(name)").param("o", ownerId)
                .query(ProfileRepository::map).list();
    }

    public Optional<Row> find(long ownerId, long id) {
        return jdbc.sql("select * from profile where owner_id = :o and id = :id").param("o", ownerId).param("id", id)
                .query(ProfileRepository::map).optional();
    }

    /** Sem filtro de dono: uso interno do agendador. */
    public Optional<Row> findById(long id) {
        return jdbc.sql("select * from profile where id = :id").param("id", id).query(ProfileRepository::map)
                .optional();
    }

    public boolean nameExists(long ownerId, String name, Long exceptId) {
        return jdbc.sql("""
                select count(*) from profile where owner_id = :o and lower(name) = lower(:n)
                   and (cast(:except as bigint) is null or id <> :except)""")
                .param("o", ownerId).param("n", name).param("except", exceptId).query(Long.class).single() > 0;
    }

    public long insert(long ownerId, String name, String complement, long sourceId, long storageId, String storageDir,
            double threshold, boolean encryption) {
        return jdbc.sql("""
                insert into profile (owner_id, name, complement, source_machine_id, storage_machine_id, storage_dir,
                                     suspect_threshold, encryption_enabled)
                values (:o, :n, :c, :s, :sm, :sd, :t, :e) returning id""")
                .param("o", ownerId).param("n", name).param("c", complement).param("s", sourceId)
                .param("sm", storageId).param("sd", storageDir).param("t", threshold).param("e", encryption)
                .query(Long.class).single();
    }

    public void update(long ownerId, long id, String name, String complement, long sourceId, long storageId,
            String storageDir, double threshold, boolean encryption) {
        jdbc.sql("""
                update profile set name = :n, complement = :c, source_machine_id = :s, storage_machine_id = :sm,
                       storage_dir = :sd, suspect_threshold = :t, encryption_enabled = :e, updated_at = now()
                 where owner_id = :o and id = :id""")
                .param("n", name).param("c", complement).param("s", sourceId).param("sm", storageId)
                .param("sd", storageDir).param("t", threshold).param("e", encryption).param("o", ownerId)
                .param("id", id).update();
    }

    public void delete(long ownerId, long id) {
        jdbc.sql("delete from profile where owner_id = :o and id = :id").param("o", ownerId).param("id", id).update();
    }

    public List<DirRow> dirs(long profileId) {
        record Flat(long dirId, int position, String path, Long machineId, String mappingPath) {
        }
        List<Flat> flat = jdbc.sql("""
                select d.id as dir_id, d.position, d.path, m.machine_id, m.path as mapping_path
                  from profile_dir d left join profile_dir_mapping m on m.profile_dir_id = d.id
                 where d.profile_id = :p order by d.position, m.machine_id""").param("p", profileId)
                .query((rs, i) -> new Flat(rs.getLong("dir_id"), rs.getInt("position"), rs.getString("path"),
                        (Long) rs.getObject("machine_id"), rs.getString("mapping_path"))).list();
        List<DirRow> out = new ArrayList<>();
        for (Flat f : flat) {
            if (out.isEmpty() || out.get(out.size() - 1).id() != f.dirId()) {
                out.add(new DirRow(f.dirId(), f.position(), f.path(), new ArrayList<>()));
            }
            if (f.machineId() != null) {
                out.get(out.size() - 1).mappings().add(new MappingRow(f.machineId(), f.mappingPath()));
            }
        }
        return out;
    }

    public void replaceDirs(long profileId, List<ProfileDtos.Dir> dirs) {
        jdbc.sql("delete from profile_dir where profile_id = :p").param("p", profileId).update();
        int pos = 1;
        for (ProfileDtos.Dir d : dirs) {
            long dirId = jdbc.sql("insert into profile_dir (profile_id, position, path) values (:p, :pos, :path) returning id")
                    .param("p", profileId).param("pos", pos++).param("path", d.path()).query(Long.class).single();
            if (d.mappings() != null) {
                for (ProfileDtos.Mapping m : d.mappings()) {
                    jdbc.sql("insert into profile_dir_mapping (profile_dir_id, machine_id, path) values (:d, :m, :path)")
                            .param("d", dirId).param("m", m.machineId()).param("path", m.path()).update();
                }
            }
        }
    }

    public Optional<ScheduleRow> backupSchedule(long profileId) {
        return jdbc.sql("select time_of_day, enabled from backup_schedule where profile_id = :p").param("p", profileId)
                .query((rs, i) -> new ScheduleRow(rs.getTime(1).toLocalTime(), rs.getBoolean(2))).optional();
    }

    public void saveBackupSchedule(long profileId, LocalTime time, boolean enabled) {
        jdbc.sql("""
                insert into backup_schedule (profile_id, time_of_day, enabled) values (:p, :t, :e)
                on conflict (profile_id) do update set time_of_day = excluded.time_of_day, enabled = excluded.enabled""")
                .param("p", profileId).param("t", Time.valueOf(time)).param("e", enabled).update();
    }

    public void deleteBackupSchedule(long profileId) {
        jdbc.sql("delete from backup_schedule where profile_id = :p").param("p", profileId).update();
    }

    /** Perfis com backup agendado e ativo (para recriar os gatilhos na inicialização). */
    public List<Long> scheduledProfileIds() {
        return jdbc.sql("select profile_id from backup_schedule where enabled").query(Long.class).list();
    }

    public Optional<Instant> instantOrNull(Timestamp ts) {
        return Optional.ofNullable(ts).map(Timestamp::toInstant);
    }
}
