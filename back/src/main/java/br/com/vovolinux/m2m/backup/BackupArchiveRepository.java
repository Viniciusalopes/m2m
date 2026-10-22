package br.com.vovolinux.m2m.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BackupArchiveRepository {

    static final String COLUMNS = """
            id, owner_id, profile_id, profile_name_snapshot, type, status, accepted, pinned, storage_machine_id,
            storage_machine_name, storage_dir, file_name, source_machine_name, source_protocol, full_fidelity,
            item_count, file_count, total_bytes, archive_bytes, sha256, encrypted, mapping::text as mapping,
            warnings::text as warnings, error, job_id, dest_machine_name, created_at, finished_at""";

    private final JdbcClient jdbc;

    public BackupArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static BackupArchive map(ResultSet rs, int i) throws SQLException {
        Timestamp fin = rs.getTimestamp("finished_at");
        return new BackupArchive(rs.getLong("id"), rs.getLong("owner_id"), (Long) rs.getObject("profile_id"),
                rs.getString("profile_name_snapshot"), rs.getString("type"), rs.getString("status"),
                rs.getBoolean("accepted"), rs.getBoolean("pinned"), (Long) rs.getObject("storage_machine_id"),
                rs.getString("storage_machine_name"), rs.getString("storage_dir"), rs.getString("file_name"),
                rs.getString("source_machine_name"), rs.getString("source_protocol"), rs.getBoolean("full_fidelity"),
                rs.getLong("item_count"), rs.getLong("file_count"), rs.getLong("total_bytes"),
                rs.getLong("archive_bytes"), rs.getString("sha256"), rs.getBoolean("encrypted"),
                rs.getString("mapping"), rs.getString("warnings"), rs.getString("error"),
                (Long) rs.getObject("job_id"), rs.getString("dest_machine_name"),
                rs.getTimestamp("created_at").toInstant(), fin == null ? null : fin.toInstant());
    }

    public List<BackupArchive> byProfile(long ownerId, long profileId) {
        return jdbc.sql("select " + COLUMNS + " from backup_archive where owner_id = :o and profile_id = :p order by id")
                .param("o", ownerId).param("p", profileId).query(BackupArchiveRepository::map).list();
    }

    public Optional<BackupArchive> find(long ownerId, long id) {
        return jdbc.sql("select " + COLUMNS + " from backup_archive where owner_id = :o and id = :id")
                .param("o", ownerId).param("id", id).query(BackupArchiveRepository::map).optional();
    }

    /** O perfil deixa de existir mas os backups ficam, como "órfãos". */
    public void orphanize(long profileId) {
        jdbc.sql("update backup_archive set profile_id = null where profile_id = :p").param("p", profileId).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from backup_archive where id = :id").param("id", id).update();
    }

}
