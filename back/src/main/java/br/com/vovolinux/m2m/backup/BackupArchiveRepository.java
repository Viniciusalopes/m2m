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
            warnings::text as warnings, error, job_id, dest_machine_name, created_at, finished_at\s""";

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

    /** Backups cujo arquivo deve existir: tudo que não é ausente nem falha de gravação (D30). */
    public List<BackupArchive> verifiable(long ownerId) {
        return jdbc.sql("select " + COLUMNS + " from backup_archive where owner_id = :o"
                + " and status in ('COMPLETED', 'COMPLETED_WITH_WARNINGS', 'SUSPECT', 'CORRUPTED') order by id")
                .param("o", ownerId).query(BackupArchiveRepository::map).list();
    }

    /** Backups registrados num diretório de armazenamento de uma máquina (reindexação, D26). */
    public List<BackupArchive> inDirectory(long ownerId, long machineId, String dir) {
        return jdbc.sql("select " + COLUMNS
                + " from backup_archive where owner_id = :o and storage_machine_id = :m and storage_dir = :d")
                .param("o", ownerId).param("m", machineId).param("d", dir).query(BackupArchiveRepository::map).list();
    }

    public Optional<BackupArchive> find(long ownerId, long id) {
        return jdbc.sql("select " + COLUMNS + " from backup_archive where owner_id = :o and id = :id")
                .param("o", ownerId).param("id", id).query(BackupArchiveRepository::map).optional();
    }

    public record NewArchive(
            long ownerId,
            Long profileId,
            String profileName,
            String type,
            String status,
            boolean accepted,
            Long storageMachineId,
            String storageMachineName,
            String storageDir,
            String fileName,
            String sourceMachineName,
            String sourceProtocol,
            boolean fullFidelity,
            long itemCount,
            long fileCount,
            long totalBytes,
            long archiveBytes,
            String sha256,
            boolean encrypted,
            String mappingJson,
            String warningsJson,
            String error,
            Long jobId,
            String destMachineName) {
    }

    public long insert(NewArchive a) {
        return jdbc.sql("""
                insert into backup_archive (owner_id, profile_id, profile_name_snapshot, type, status, accepted,
                       storage_machine_id, storage_machine_name, storage_dir, file_name, source_machine_name,
                       source_protocol, full_fidelity, item_count, file_count, total_bytes, archive_bytes, sha256,
                       encrypted, mapping, warnings, error, job_id, dest_machine_name, finished_at)
                values (:owner, :profile, :pname, :type, :status, :accepted, :sm, :smn, :sdir, :file, :src, :proto,
                        :full, :items, :files, :bytes, :abytes, :sha, :enc, cast(:mapping as jsonb),
                        cast(:warnings as jsonb), :error, :job, :dest, now())
                returning id""")
                .param("owner", a.ownerId()).param("profile", a.profileId()).param("pname", a.profileName())
                .param("type", a.type()).param("status", a.status()).param("accepted", a.accepted())
                .param("sm", a.storageMachineId()).param("smn", a.storageMachineName())
                .param("sdir", a.storageDir()).param("file", a.fileName()).param("src", a.sourceMachineName())
                .param("proto", a.sourceProtocol()).param("full", a.fullFidelity()).param("items", a.itemCount())
                .param("files", a.fileCount()).param("bytes", a.totalBytes()).param("abytes", a.archiveBytes())
                .param("sha", a.sha256()).param("enc", a.encrypted()).param("mapping", a.mappingJson())
                .param("warnings", a.warningsJson()).param("error", a.error()).param("job", a.jobId())
                .param("dest", a.destMachineName()).query(Long.class).single();
    }

    /** Último backup REGULAR aceito do perfil (base da regra de backup suspeito, D25). */
    public Optional<BackupArchive> lastAccepted(long profileId) {
        return jdbc.sql("select " + COLUMNS + """
                 from backup_archive where profile_id = :p and type = 'REGULAR' and accepted
                   and status in ('COMPLETED', 'COMPLETED_WITH_WARNINGS') order by created_at desc, id desc limit 1""")
                .param("p", profileId).query(BackupArchiveRepository::map).optional();
    }

    /** Aceitos e não fixados do perfil/tipo, do mais novo ao mais antigo (candidatos à retenção). */
    public List<BackupArchive> acceptedUnpinned(long profileId, String type) {
        return jdbc.sql("select " + COLUMNS + """
                 from backup_archive where profile_id = :p and type = :t and accepted and not pinned
                   and status in ('COMPLETED', 'COMPLETED_WITH_WARNINGS') order by created_at desc, id desc""")
                .param("p", profileId).param("t", type).query(BackupArchiveRepository::map).list();
    }

    /** Retenção dos backups de segurança: por perfil, máquina de destino e tipo. */
    public List<BackupArchive> safetyCandidates(long profileId, String type, String destMachineName) {
        return jdbc.sql("select " + COLUMNS + """
                 from backup_archive where profile_id = :p and type = :t and dest_machine_name = :d and not pinned
                   and status in ('COMPLETED', 'COMPLETED_WITH_WARNINGS') order by created_at desc, id desc""")
                .param("p", profileId).param("t", type).param("d", destMachineName)
                .query(BackupArchiveRepository::map).list();
    }

    public void setPinned(long id, boolean pinned) {
        jdbc.sql("update backup_archive set pinned = :p where id = :id").param("p", pinned).param("id", id).update();
    }

    public void setAccepted(long id, boolean accepted, String status) {
        jdbc.sql("update backup_archive set accepted = :a, status = :s where id = :id").param("a", accepted)
                .param("s", status).param("id", id).update();
    }

    public void setStatus(long id, String status, String error) {
        jdbc.sql("update backup_archive set status = :s, error = :e where id = :id").param("s", status)
                .param("e", error).param("id", id).update();
    }

    /** Filtros opcionais; {@code orphan} = sem perfil. */
    public List<BackupArchive> list(long ownerId, Long profileId, String status, String type, Boolean orphan,
            int limit, int offset) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from backup_archive where owner_id = :o");
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("o", ownerId);
        if (profileId != null) {
            sql.append(" and profile_id = :p");
            p.put("p", profileId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = :s");
            p.put("s", status);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" and type = :t");
            p.put("t", type);
        }
        if (Boolean.TRUE.equals(orphan)) {
            sql.append(" and profile_id is null");
        }
        sql.append(" order by created_at desc, id desc limit :limit offset :offset");
        p.put("limit", limit);
        p.put("offset", offset);
        return jdbc.sql(sql.toString()).params(p).query(BackupArchiveRepository::map).list();
    }

    /** Há job em execução ou na fila usando este backup (restauração, verificação...)? */
    public boolean inUseByActiveJob(long archiveId) {
        return jdbc.sql("""
                select count(*) from job where status in ('QUEUED', 'RUNNING')
                   and (params ->> 'archiveId') = cast(:id as text)""").param("id", archiveId).query(Long.class)
                .single() > 0;
    }

    /** O perfil deixa de existir mas os backups ficam, como "órfãos". */
    public void orphanize(long profileId) {
        jdbc.sql("update backup_archive set profile_id = null where profile_id = :p").param("p", profileId).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from backup_archive where id = :id").param("id", id).update();
    }

}
