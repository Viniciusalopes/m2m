package br.com.vovolinux.m2m.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AppUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp locked = rs.getTimestamp("locked_until");
        return new AppUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("enabled"),
                rs.getInt("failed_attempts"),
                locked == null ? null : locked.toInstant());
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("select * from app_user where username = :u").param("u", username)
                .query(UserRepository::map).optional();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.sql("select * from app_user where id = :id").param("id", id)
                .query(UserRepository::map).optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from app_user").query(Long.class).single();
    }

    public long create(String username, String email, String passwordHash, String role) {
        return jdbc.sql("""
                insert into app_user (username, email, password_hash, role)
                values (:u, :e, :p, :r) returning id""")
                .param("u", username).param("e", email).param("p", passwordHash).param("r", role)
                .query(Long.class).single();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("update app_user set password_hash = :p, updated_at = now() where id = :id")
                .param("p", passwordHash).param("id", id).update();
    }

    public void updateEmail(long id, String email) {
        jdbc.sql("update app_user set email = :e, updated_at = now() where id = :id")
                .param("e", email).param("id", id).update();
    }

    /** Zera o contador de falhas e o bloqueio. */
    public void resetAttempts(long id) {
        jdbc.sql("update app_user set failed_attempts = 0, locked_until = null where id = :id")
                .param("id", id).update();
    }

    /** Soma uma falha e bloqueia ao atingir o limite. */
    public void registerFailure(long id, int limit, long lockSeconds) {
        jdbc.sql("""
                update app_user
                   set failed_attempts = failed_attempts + 1,
                       locked_until = case when failed_attempts + 1 >= :limit
                                           then now() + make_interval(secs => :secs) else locked_until end
                 where id = :id""")
                .param("limit", limit).param("secs", (double) lockSeconds).param("id", id).update();
    }
}
