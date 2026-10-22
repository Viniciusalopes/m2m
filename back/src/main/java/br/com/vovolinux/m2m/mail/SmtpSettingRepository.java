package br.com.vovolinux.m2m.mail;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SmtpSettingRepository {

    private final JdbcClient jdbc;

    public SmtpSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SmtpSetting> find(long ownerId) {
        return jdbc.sql("select * from user_smtp_setting where owner_id = :o").param("o", ownerId)
                .query((rs, i) -> new SmtpSetting(rs.getLong("owner_id"), rs.getString("host"), rs.getInt("port"),
                        rs.getString("security"), rs.getString("username"), rs.getBytes("password_enc"),
                        rs.getString("from_address"), rs.getBoolean("enabled"))).optional();
    }

    public void save(SmtpSetting s) {
        jdbc.sql("""
                insert into user_smtp_setting (owner_id, host, port, security, username, password_enc, from_address, enabled)
                values (:o, :h, :p, :s, :u, :pw, :f, :e)
                on conflict (owner_id) do update set host = excluded.host, port = excluded.port,
                       security = excluded.security, username = excluded.username,
                       password_enc = excluded.password_enc, from_address = excluded.from_address,
                       enabled = excluded.enabled, updated_at = now()""")
                .param("o", s.ownerId()).param("h", s.host()).param("p", s.port()).param("s", s.security())
                .param("u", s.username()).param("pw", s.passwordEnc()).param("f", s.fromAddress())
                .param("e", s.enabled()).update();
    }
}
