package br.com.vovolinux.m2m.settings;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Configurações da instalação em {@code app_setting} (valor JSON como texto). */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("select value::text from app_setting where key = :k").param("k", key)
                .query(String.class).optional();
    }

    public void put(String key, String jsonValue) {
        jdbc.sql("""
                insert into app_setting (key, value) values (:k, cast(:v as jsonb))
                on conflict (key) do update set value = excluded.value, updated_at = now()""")
                .param("k", key).param("v", jsonValue).update();
    }
}
