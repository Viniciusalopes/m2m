package br.com.vovolinux.m2m.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    static final String COLUMNS = """
            id, owner_id, batch_id, op, kind, status, src_machine_id, src_machine_name, src_path, dst_machine_id,
            dst_machine_name, dst_path, size, bytes_done, src_mtime, src_size_start, src_mode, link_target,
            resume_offset, tmp_path, append_final, conflict_action, skip_reason, note, error_code,
            error_params::text as error_params, created_at, started_at, finished_at""";

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static TransferItem map(ResultSet rs, int row) throws SQLException {
        return new TransferItem(rs.getLong("id"), rs.getLong("owner_id"), rs.getObject("batch_id", UUID.class),
                rs.getString("op"), rs.getString("kind"), rs.getString("status"), (Long) rs.getObject("src_machine_id"),
                rs.getString("src_machine_name"), rs.getString("src_path"), (Long) rs.getObject("dst_machine_id"),
                rs.getString("dst_machine_name"), rs.getString("dst_path"), rs.getLong("size"),
                rs.getLong("bytes_done"), (Long) rs.getObject("src_mtime"), (Long) rs.getObject("src_size_start"),
                (Integer) rs.getObject("src_mode"), rs.getString("link_target"), rs.getLong("resume_offset"),
                rs.getString("tmp_path"), rs.getBoolean("append_final"), rs.getString("conflict_action"),
                rs.getString("skip_reason"), rs.getString("note"), rs.getString("error_code"),
                rs.getString("error_params"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")));
    }

    /** Dados de um item novo; {@code status} é QUEUED, ou SKIPPED/COMPLETED quando já nasce resolvido. */
    public record NewItem(long ownerId, UUID batchId, String op, String kind, String status, Long srcMachineId,
            String srcMachineName, String srcPath, Long dstMachineId, String dstMachineName, String dstPath, long size,
            Long srcMtime, Integer srcMode, String linkTarget, boolean appendFinal, String conflictAction,
            String skipReason) {
    }

    public long insert(NewItem n) {
        boolean finished = !TransferItem.QUEUED.equals(n.status());
        return jdbc.sql("""
                insert into transfer_item (owner_id, batch_id, op, kind, status, src_machine_id, src_machine_name,
                       src_path, dst_machine_id, dst_machine_name, dst_path, size, src_mtime, src_mode, link_target,
                       append_final, conflict_action, skip_reason, finished_at)
                values (:o, :b, :op, :k, :st, :sm, :smn, :sp, :dm, :dmn, :dp, :sz, :mt, :mode, :lt, :af, :ca, :sr,
                        case when :fin then now() end)
                returning id""")
                .param("o", n.ownerId()).param("b", n.batchId()).param("op", n.op()).param("k", n.kind())
                .param("st", n.status()).param("sm", n.srcMachineId()).param("smn", n.srcMachineName())
                .param("sp", n.srcPath()).param("dm", n.dstMachineId()).param("dmn", n.dstMachineName())
                .param("dp", n.dstPath()).param("sz", n.size()).param("mt", n.srcMtime()).param("mode", n.srcMode())
                .param("lt", n.linkTarget()).param("af", n.appendFinal()).param("ca", n.conflictAction())
                .param("sr", n.skipReason()).param("fin", finished).query(Long.class).single();
    }

    public Optional<TransferItem> find(long ownerId, long id) {
        return jdbc.sql("select " + COLUMNS + " from transfer_item where owner_id = :o and id = :id")
                .param("o", ownerId).param("id", id).query(TransferRepository::map).optional();
    }

    public Optional<TransferItem> findAny(long id) {
        return jdbc.sql("select " + COLUMNS + " from transfer_item where id = :id").param("id", id)
                .query(TransferRepository::map).optional();
    }

    public Optional<TransferItem> claim(long id) {
        return jdbc.sql("""
                update transfer_item set status = 'TRANSFERRING', started_at = now(), finished_at = null,
                       error_code = null, error_params = null
                 where id = :id and status = 'QUEUED' returning """ + " " + COLUMNS).param("id", id)
                .query(TransferRepository::map).optional();
    }

    public List<TransferItem> list(long ownerId, String status, UUID batch, int limit, int offset) {
        return jdbc.sql("select " + COLUMNS + """
                \s  from transfer_item where owner_id = :o and op = 'COPY'
                   and (cast(:st as text) is null or status = :st)
                   and (cast(:b as uuid) is null or batch_id = :b)
                 order by id desc limit :l offset :off""")
                .param("o", ownerId).param("st", status).param("b", batch).param("l", limit).param("off", offset)
                .query(TransferRepository::map).list();
    }

    /** Histórico de operações de arquivo (criar, renomear, excluir). */
    public List<TransferItem> operations(long ownerId, int limit) {
        return jdbc.sql("select " + COLUMNS
                + " from transfer_item where owner_id = :o and op <> 'COPY' order by id desc limit :l")
                .param("o", ownerId).param("l", limit).query(TransferRepository::map).list();
    }

    public void markStarted(long id, long srcSizeStart, long srcMtime, String tmpPath, long resumeOffset, String note) {
        jdbc.sql("""
                update transfer_item set src_size_start = :ss, src_mtime = :mt, tmp_path = :tmp, resume_offset = :ro,
                       bytes_done = :ro, note = :note where id = :id""")
                .param("ss", srcSizeStart).param("mt", srcMtime).param("tmp", tmpPath).param("ro", resumeOffset)
                .param("note", note).param("id", id).update();
    }

    public void saveProgress(long id, long bytesDone) {
        jdbc.sql("update transfer_item set bytes_done = :b where id = :id and status = 'TRANSFERRING'")
                .param("b", bytesDone).param("id", id).update();
    }

    public void finish(long id, String status, long bytesDone, long resumeOffset, String skipReason, String errorCode,
            String errorParamsJson, String note) {
        jdbc.sql("""
                update transfer_item set status = :st, bytes_done = :b, resume_offset = :ro, skip_reason = :sr,
                       error_code = :ec, error_params = cast(:ep as jsonb), note = coalesce(:note, note),
                       finished_at = now() where id = :id""")
                .param("st", status).param("b", bytesDone).param("ro", resumeOffset).param("sr", skipReason)
                .param("ec", errorCode).param("ep", errorParamsJson).param("note", note).param("id", id).update();
    }

    public void clearTmp(long id) {
        jdbc.sql("update transfer_item set tmp_path = null where id = :id").param("id", id).update();
    }

    /** Volta um item reiniciável para a fila (nova tentativa ou retomada). */
    public boolean requeue(long id, long resumeOffset, String note) {
        return jdbc.sql("""
                update transfer_item set status = 'QUEUED', resume_offset = :ro, bytes_done = :ro, note = :note,
                       finished_at = null, error_code = null, error_params = null, skip_reason = null
                 where id = :id and status in ('FAILED', 'CANCELED', 'INTERRUPTED')""")
                .param("ro", resumeOffset).param("note", note).param("id", id).update() > 0;
    }

    public boolean cancelQueued(long id) {
        return jdbc.sql("""
                update transfer_item set status = 'CANCELED', finished_at = now() where id = :id and status = 'QUEUED'""")
                .param("id", id).update() > 0;
    }

    public List<TransferItem> queued(long ownerId) {
        return jdbc.sql("select " + COLUMNS + " from transfer_item where owner_id = :o and status = 'QUEUED' order by id")
                .param("o", ownerId).query(TransferRepository::map).list();
    }

    public List<TransferItem> running(long ownerId) {
        return jdbc.sql("select " + COLUMNS
                + " from transfer_item where owner_id = :o and status = 'TRANSFERRING' order by id")
                .param("o", ownerId).query(TransferRepository::map).list();
    }

    public List<TransferItem> restartable(long ownerId, String status) {
        return jdbc.sql("select " + COLUMNS
                + " from transfer_item where owner_id = :o and op = 'COPY' and status = :st order by id")
                .param("o", ownerId).param("st", status).query(TransferRepository::map).list();
    }

    public long pendingInBatch(UUID batch) {
        return jdbc.sql("select count(*) from transfer_item where batch_id = :b and status in ('QUEUED', 'TRANSFERRING')")
                .param("b", batch).query(Long.class).single();
    }

    public List<TransferItem> batchDirs(UUID batch) {
        return jdbc.sql("select " + COLUMNS
                + " from transfer_item where batch_id = :b and kind = 'DIR' and status = 'COMPLETED' order by id desc")
                .param("b", batch).query(TransferRepository::map).list();
    }

    /** Ao subir: o que estava transferindo foi interrompido pelo reinício. */
    public List<TransferItem> markTransferringInterrupted() {
        return jdbc.sql("""
                update transfer_item set status = 'INTERRUPTED', finished_at = now(), resume_offset = bytes_done,
                       note = 'reinicio' where status = 'TRANSFERRING' returning """ + " " + COLUMNS)
                .query(TransferRepository::map).list();
    }

    public List<TransferItem> queuedWithoutPendingPublication() {
        return jdbc.sql("select " + COLUMNS + """
                \s  from transfer_item t where status = 'QUEUED' and not exists (
                       select 1 from job_outbox o where o.kind = 'TRANSFER' and o.ref_id = t.id and o.published_at is null)
                 order by id""").query(TransferRepository::map).list();
    }

    /** Itens parados há mais de {@code days} dias que ainda guardam temporário no destino. */
    public List<TransferItem> staleTemporaries(int days) {
        return jdbc.sql("select " + COLUMNS + """
                \s  from transfer_item where tmp_path is not null and status in ('FAILED', 'CANCELED', 'INTERRUPTED')
                   and finished_at < now() - make_interval(days => :d)""").param("d", days)
                .query(TransferRepository::map).list();
    }

    public void insertOutbox(long itemId) {
        jdbc.sql("""
                insert into job_outbox (kind, ref_id, exchange, routing_key) values ('TRANSFER', :r, :e, :k)""")
                .param("r", itemId).param("e", br.com.vovolinux.m2m.job.RabbitTopology.TRANSFERS)
                .param("k", br.com.vovolinux.m2m.job.RabbitTopology.RUN_KEY).update();
    }

    // ---- conjuntos de filtros ----

    public record FilterSet(long id, String name, String definition) {
    }

    public List<FilterSet> filterSets(long ownerId) {
        return jdbc.sql("select id, name, definition::text from transfer_filter_set where owner_id = :o order by lower(name)")
                .param("o", ownerId).query((rs, i) -> new FilterSet(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    public Optional<FilterSet> filterSet(long ownerId, long id) {
        return jdbc.sql("select id, name, definition::text from transfer_filter_set where owner_id = :o and id = :id")
                .param("o", ownerId).param("id", id)
                .query((rs, i) -> new FilterSet(rs.getLong(1), rs.getString(2), rs.getString(3))).optional();
    }

    public long saveFilterSet(long ownerId, String name, String definitionJson) {
        return jdbc.sql("""
                insert into transfer_filter_set (owner_id, name, definition) values (:o, :n, cast(:d as jsonb))
                on conflict (owner_id, lower(name)) do update set definition = excluded.definition, name = excluded.name
                returning id""").param("o", ownerId).param("n", name).param("d", definitionJson)
                .query(Long.class).single();
    }

    public boolean deleteFilterSet(long ownerId, long id) {
        return jdbc.sql("delete from transfer_filter_set where owner_id = :o and id = :id").param("o", ownerId)
                .param("id", id).update() > 0;
    }
}
