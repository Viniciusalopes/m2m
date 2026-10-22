package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.compare.TreeComparator;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Estado do destino após a última sincronização concluída por perfil + destino (D24): listagem compactada em zstd.
 * Na sincronização seguinte, o que mudou no destino fora do m2m é "trabalho feito no destino".
 */
@Service
public class SyncStateService {

    /** Diferenças entre o estado registrado e o destino de agora. */
    public record Changes(List<String> created, List<String> changed, List<String> removed) {

        public boolean any() {
            return !created.isEmpty() || !changed.isEmpty() || !removed.isEmpty();
        }

        public long total() {
            return created.size() + changed.size() + removed.size();
        }

        /** Amostra para mensagens (até {@code max} caminhos, com o tipo de mudança). */
        public List<String> sample(int max) {
            List<String> out = new ArrayList<>();
            created.stream().limit(max).forEach(p -> out.add("+ " + p));
            changed.stream().limit(max).forEach(p -> out.add("~ " + p));
            removed.stream().limit(max).forEach(p -> out.add("- " + p));
            return out.size() > max ? out.subList(0, max) : out;
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SyncStateService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean exists(long profileId, long destMachineId) {
        return jdbc.sql("select count(*) from sync_state where profile_id = :p and dest_machine_id = :m")
                .param("p", profileId).param("m", destMachineId).query(Long.class).single() > 0;
    }

    /** Grava a listagem atual do destino como o novo estado. */
    public void save(long profileId, long destMachineId, FileGateway dest, List<String> roots,
            CancellationToken token) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long count;
        try (ZstdOutputStream z = new ZstdOutputStream(bos, 3);
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(z, StandardCharsets.UTF_8))) {
            count = DestListing.write(dest, roots, w, json, token);
        }
        jdbc.sql("""
                insert into sync_state (profile_id, dest_machine_id, state, item_count) values (:p, :m, :s, :c)
                on conflict (profile_id, dest_machine_id) do update set state = excluded.state,
                       item_count = excluded.item_count, updated_at = now()""")
                .param("p", profileId).param("m", destMachineId).param("s", bos.toByteArray()).param("c", count)
                .update();
    }

    /** Compara o destino de agora com o estado registrado; vazio se nunca houve sincronização. */
    public Optional<Changes> changesSince(long profileId, long destMachineId, FileGateway dest, List<String> roots,
            CancellationToken token) throws IOException {
        Optional<byte[]> state = jdbc.sql("select state from sync_state where profile_id = :p and dest_machine_id = :m")
                .param("p", profileId).param("m", destMachineId).query(byte[].class).optional();
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, DestListing.Entry> old = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ZstdInputStream(new ByteArrayInputStream(state.get())), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                DestListing.Entry e = json.readValue(line, DestListing.Entry.class);
                old.put(e.dir() + "/" + e.path(), e);
            }
        }
        ByteArrayOutputStream now = new ByteArrayOutputStream();
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(now, StandardCharsets.UTF_8))) {
            DestListing.write(dest, roots, w, json, token);
        }
        List<String> created = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(now.toByteArray()),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                DestListing.Entry e = json.readValue(line, DestListing.Entry.class);
                String key = e.dir() + "/" + e.path();
                DestListing.Entry o = old.remove(key);
                String label = roots.get(e.dir() - 1) + "/" + e.path();
                if (o == null) {
                    created.add(label);
                } else if (!o.type().equals(e.type())) {
                    changed.add(label);
                } else if (e.type().equals("FILE") && (o.size() != e.size()
                        || Math.abs(o.mtime() - e.mtime()) > TreeComparator.MTIME_TOLERANCE_MILLIS)) {
                    changed.add(label);
                }
            }
        }
        List<String> removed = new ArrayList<>();
        for (DestListing.Entry o : old.values()) {
            removed.add(roots.get(o.dir() - 1) + "/" + o.path());
        }
        java.util.Collections.sort(removed);
        return Optional.of(new Changes(created, changed, removed));
    }
}
