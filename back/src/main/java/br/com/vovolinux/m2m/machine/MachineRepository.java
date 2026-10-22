package br.com.vovolinux.m2m.machine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Todas as consultas filtram pelo dono (owner_id) — D21. */
@Repository
public class MachineRepository {

    private final JdbcClient jdbc;

    public MachineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Machine map(ResultSet rs, int row) throws SQLException {
        Timestamp lastTest = rs.getTimestamp("last_test_at");
        return new Machine(
                rs.getLong("id"),
                rs.getLong("owner_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("host"),
                Protocol.valueOf(rs.getString("protocol")),
                rs.getInt("port"),
                rs.getString("ftps_mode"),
                rs.getString("username"),
                rs.getString("auth_type"),
                rs.getBytes("password_enc"),
                rs.getInt("max_connections"),
                rs.getString("capabilities"),
                lastTest == null ? null : lastTest.toInstant(),
                rs.getString("last_test"));
    }

    private static final String COLUMNS = """
            id, owner_id, name, description, host, protocol, port, ftps_mode, username, auth_type, password_enc,
            max_connections, capabilities::text as capabilities, last_test_at, last_test::text as last_test""";

    public List<Machine> findAll(long ownerId) {
        return jdbc.sql("select " + COLUMNS + " from machine where owner_id = :o order by lower(name)")
                .param("o", ownerId).query(MachineRepository::map).list();
    }

    public Optional<Machine> find(long ownerId, long id) {
        return jdbc.sql("select " + COLUMNS + " from machine where owner_id = :o and id = :id")
                .param("o", ownerId).param("id", id).query(MachineRepository::map).optional();
    }

    public boolean nameExists(long ownerId, String name, Long exceptId) {
        return jdbc.sql("""
                select count(*) from machine where owner_id = :o and lower(name) = lower(:n)
                   and (cast(:except as bigint) is null or id <> :except)""")
                .param("o", ownerId).param("n", name).param("except", exceptId)
                .query(Long.class).single() > 0;
    }

    public boolean localExists() {
        return jdbc.sql("select count(*) from machine where protocol = 'LOCAL'").query(Long.class).single() > 0;
    }

    public long count(long ownerId) {
        return jdbc.sql("select count(*) from machine where owner_id = :o").param("o", ownerId)
                .query(Long.class).single();
    }

    public long insert(Machine m) {
        return jdbc.sql("""
                insert into machine (owner_id, name, description, host, protocol, port, ftps_mode, username, auth_type,
                                     password_enc, max_connections)
                values (:owner, :name, :desc, :host, :protocol, :port, :ftps, :user, :auth, :pw, :max)
                returning id""")
                .param("owner", m.ownerId()).param("name", m.name()).param("desc", m.description())
                .param("host", m.host()).param("protocol", m.protocol().name()).param("port", m.port())
                .param("ftps", m.ftpsMode()).param("user", m.username()).param("auth", m.authType())
                .param("pw", m.passwordEnc()).param("max", m.maxConnections()).query(Long.class).single();
    }

    public void update(Machine m) {
        jdbc.sql("""
                update machine set name = :name, description = :desc, host = :host, protocol = :protocol,
                       port = :port, ftps_mode = :ftps, username = :user, auth_type = :auth, password_enc = :pw,
                       max_connections = :max, updated_at = now()
                 where owner_id = :owner and id = :id""")
                .param("owner", m.ownerId()).param("id", m.id()).param("name", m.name())
                .param("desc", m.description()).param("host", m.host()).param("protocol", m.protocol().name())
                .param("port", m.port()).param("ftps", m.ftpsMode()).param("user", m.username())
                .param("auth", m.authType()).param("pw", m.passwordEnc()).param("max", m.maxConnections()).update();
    }

    public void delete(long ownerId, long id) {
        jdbc.sql("delete from machine where owner_id = :o and id = :id").param("o", ownerId).param("id", id).update();
    }

    public void saveTest(long ownerId, long id, String capabilitiesJson, String testJson) {
        jdbc.sql("""
                update machine set capabilities = cast(:caps as jsonb), last_test = cast(:test as jsonb),
                       last_test_at = now()
                 where owner_id = :o and id = :id""")
                .param("caps", capabilitiesJson).param("test", testJson).param("o", ownerId).param("id", id).update();
    }
}
