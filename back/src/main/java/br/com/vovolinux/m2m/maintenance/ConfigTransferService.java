package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.profile.ProfileService;
import br.com.vovolinux.m2m.sync.SyncService;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exportação e importação da configuração do usuário (D32): máquinas (com credenciais), identidades de servidor,
 * chave SSH, perfis, mapeamentos, agendamentos e SMTP. O arquivo todo vai cifrado por senha; as credenciais saem da
 * chave mestra e entram cifradas pela senha. Máquinas e perfis são casados pelo nome.
 */
@Service
public class ConfigTransferService {

    public static final int MIN_PASSWORD = 8;

    public record Preview(List<String> machinesCreate, List<String> machinesReplace, List<String> profilesCreate,
            List<String> profilesReplace, boolean smtp, boolean sshIdentity, int exportedProfiles) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final PasswordCipher cipher;
    private final JsonMapper json;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public ConfigTransferService(JdbcClient jdbc, CryptoService crypto, PasswordCipher cipher, JsonMapper json,
            AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "configuracao.senha-curta",
                    Map.of("minimo", MIN_PASSWORD));
        }
    }

    private static String b64(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] decryptMaster(byte[] enc) {
        return enc == null ? null : crypto.decrypt(enc);
    }

    // ---- exportar ----

    @Transactional
    public String export(long ownerId, String password) {
        checkPassword(password);
        ObjectNode root = json.createObjectNode();
        root.put("exportadoEm", java.time.Instant.now().toString());

        ArrayNode machines = root.putArray("maquinas");
        for (Map<String, Object> m : jdbc.sql("""
                select m.id, m.name, m.description, m.host, m.protocol, m.port, m.ftps_mode, m.username, m.auth_type,
                       m.password_enc, m.max_connections, s.kind, s.fingerprint
                  from machine m left join server_identity s on s.machine_id = m.id and s.confirmed_at is not null
                 where m.owner_id = :o and m.protocol <> 'LOCAL' order by m.id""").param("o", ownerId).query()
                .listOfRows()) {
            ObjectNode n = machines.addObject();
            n.put("nome", (String) m.get("name"));
            n.put("descricao", (String) m.get("description"));
            n.put("host", (String) m.get("host"));
            n.put("protocolo", (String) m.get("protocol"));
            n.put("porta", ((Number) m.get("port")).intValue());
            n.put("modoFtps", (String) m.get("ftps_mode"));
            n.put("usuario", (String) m.get("username"));
            n.put("autenticacao", (String) m.get("auth_type"));
            n.put("senha", b64(decryptMaster((byte[]) m.get("password_enc"))));
            n.put("conexoes", ((Number) m.get("max_connections")).intValue());
            if (m.get("fingerprint") != null) {
                ObjectNode id = n.putObject("identidade");
                id.put("tipo", (String) m.get("kind"));
                id.put("impressaoDigital", (String) m.get("fingerprint"));
            }
        }

        jdbc.sql("select algorithm, public_key, private_key_enc from ssh_identity where owner_id = :o")
                .param("o", ownerId).query().listOfRows().stream().findFirst().ifPresent(r -> {
                    ObjectNode k = root.putObject("chaveSsh");
                    k.put("algoritmo", (String) r.get("algorithm"));
                    k.put("publica", (String) r.get("public_key"));
                    k.put("privada", b64(decryptMaster((byte[]) r.get("private_key_enc"))));
                });

        // nome da LOCAL nesta instalação: os perfis a citam pelo nome, e o destino pode ter outro nome para ela
        jdbc.sql("select name from machine where owner_id = :o and protocol = 'LOCAL'").param("o", ownerId)
                .query(String.class).optional().ifPresent(nome -> root.put("maquinaLocal", nome));

        ArrayNode profiles = root.putArray("perfis");
        for (Map<String, Object> p : jdbc.sql("""
                select p.id, p.name, p.complement, sm.name as source, st.name as storage, p.storage_dir,
                       p.suspect_threshold, p.encryption_enabled, b.time_of_day, b.enabled
                  from profile p join machine sm on sm.id = p.source_machine_id
                  join machine st on st.id = p.storage_machine_id
                  left join backup_schedule b on b.profile_id = p.id
                 where p.owner_id = :o order by p.id""").param("o", ownerId).query().listOfRows()) {
            long pid = ((Number) p.get("id")).longValue();
            ObjectNode n = profiles.addObject();
            n.put("nome", (String) p.get("name"));
            n.put("complemento", (String) p.get("complement"));
            n.put("origem", (String) p.get("source"));
            n.put("armazenamento", (String) p.get("storage"));
            n.put("diretorioArmazenamento", (String) p.get("storage_dir"));
            n.put("limiarSuspeito", ((Number) p.get("suspect_threshold")).doubleValue());
            n.put("criptografia", (Boolean) p.get("encryption_enabled"));
            if (p.get("time_of_day") != null) {
                ObjectNode b = n.putObject("agendamentoBackup");
                b.put("horario", p.get("time_of_day").toString());
                b.put("ativo", (Boolean) p.get("enabled"));
            }
            ArrayNode dirs = n.putArray("diretorios");
            for (Map<String, Object> d : jdbc.sql("select id, position, path from profile_dir where profile_id = :p order by position")
                    .param("p", pid).query().listOfRows()) {
                ObjectNode dn = dirs.addObject();
                dn.put("caminho", (String) d.get("path"));
                ArrayNode maps = dn.putArray("mapeamentos");
                for (Map<String, Object> mp : jdbc.sql("""
                        select m.name, x.path from profile_dir_mapping x join machine m on m.id = x.machine_id
                         where x.profile_dir_id = :d order by x.id""").param("d", ((Number) d.get("id")).longValue())
                        .query().listOfRows()) {
                    ObjectNode mn = maps.addObject();
                    mn.put("maquina", (String) mp.get("name"));
                    mn.put("caminho", (String) mp.get("path"));
                }
            }
            ArrayNode syncs = n.putArray("agendamentosSincronizacao");
            for (Map<String, Object> s : jdbc.sql("""
                    select m.name, s.time_of_day, s.enabled, s.max_deletions_percent, s.max_deletions_abs
                      from sync_schedule s join machine m on m.id = s.dest_machine_id where s.profile_id = :p order by s.id""")
                    .param("p", pid).query().listOfRows()) {
                ObjectNode sn = syncs.addObject();
                sn.put("destino", (String) s.get("name"));
                sn.put("horario", s.get("time_of_day").toString());
                sn.put("ativo", (Boolean) s.get("enabled"));
                sn.put("limitePercentual", ((Number) s.get("max_deletions_percent")).intValue());
                sn.put("limiteAbsoluto", ((Number) s.get("max_deletions_abs")).intValue());
            }
        }

        // chaves de criptografia dos perfis (inclusive de perfis excluídos): sem elas, backups cifrados não abrem
        ArrayNode chaves = root.putArray("chavesPerfis");
        for (Map<String, Object> k : jdbc.sql("""
                select key_id, profile_name, keyset_enc, active from profile_key where owner_id = :o order by id""")
                .param("o", ownerId).query().listOfRows()) {
            ObjectNode kn = chaves.addObject();
            kn.put("idChave", (String) k.get("key_id"));
            kn.put("perfil", (String) k.get("profile_name"));
            kn.put("chave", b64(decryptMaster((byte[]) k.get("keyset_enc"))));
            kn.put("ativa", (Boolean) k.get("active"));
        }

        jdbc.sql("select host, port, security, username, password_enc, from_address, enabled from user_smtp_setting where owner_id = :o")
                .param("o", ownerId).query().listOfRows().stream().findFirst().ifPresent(r -> {
                    ObjectNode s = root.putObject("smtp");
                    s.put("host", (String) r.get("host"));
                    s.put("porta", ((Number) r.get("port")).intValue());
                    s.put("seguranca", (String) r.get("security"));
                    s.put("usuario", (String) r.get("username"));
                    s.put("senha", b64(decryptMaster((byte[]) r.get("password_enc"))));
                    s.put("remetente", (String) r.get("from_address"));
                    s.put("ativo", (Boolean) r.get("enabled"));
                });

        audit.record("CONFIG_EXPORT", "configuracao", AuditService.SUCESSO,
                Map.of("maquinas", machines.size(), "perfis", profiles.size()));
        return cipher.encrypt(json.writeValueAsString(root), password.toCharArray());
    }

    // ---- importar ----

    private JsonNode open(String content, String password) {
        checkPassword(password);
        try {
            return json.readTree(cipher.decrypt(content, password.toCharArray()));
        } catch (PasswordCipher.WrongPassword e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "configuracao.senha-incorreta");
        } catch (PasswordCipher.InvalidFile e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "configuracao.arquivo-invalido");
        }
    }

    private boolean machineExists(long ownerId, String name) {
        return jdbc.sql("select count(*) from machine where owner_id = :o and name = :n").param("o", ownerId)
                .param("n", name).query(Long.class).single() > 0;
    }

    private boolean profileExists(long ownerId, String name) {
        return jdbc.sql("select count(*) from profile where owner_id = :o and lower(name) = lower(:n)")
                .param("o", ownerId).param("n", name).query(Long.class).single() > 0;
    }

    @Transactional(readOnly = true)
    public Preview preview(long ownerId, String content, String password) {
        JsonNode root = open(content, password);
        List<String> mc = new ArrayList<>();
        List<String> mr = new ArrayList<>();
        for (JsonNode m : root.path("maquinas")) {
            (machineExists(ownerId, m.path("nome").asText()) ? mr : mc).add(m.path("nome").asText());
        }
        List<String> pc = new ArrayList<>();
        List<String> pr = new ArrayList<>();
        for (JsonNode p : root.path("perfis")) {
            (profileExists(ownerId, p.path("nome").asText()) ? pr : pc).add(p.path("nome").asText());
        }
        return new Preview(mc, mr, pc, pr, root.has("smtp"), root.has("chaveSsh"), root.path("perfis").size());
    }

    @Transactional
    public Preview apply(long ownerId, String content, String password) {
        JsonNode root = open(content, password);
        Preview preview = preview(ownerId, content, password);

        for (JsonNode m : root.path("maquinas")) {
            importMachine(ownerId, m);
        }
        if (root.has("chaveSsh")) {
            JsonNode k = root.get("chaveSsh");
            byte[] priv = crypto.encrypt(Base64.getDecoder().decode(k.path("privada").asText()));
            jdbc.sql("delete from ssh_identity where owner_id = :o").param("o", ownerId).update();
            jdbc.sql("insert into ssh_identity (owner_id, algorithm, public_key, private_key_enc) values (:o, :a, :pub, :priv)")
                    .param("o", ownerId).param("a", k.path("algoritmo").asText()).param("pub", k.path("publica").asText())
                    .param("priv", priv).update();
        }
        UnaryOperator<String> nome = nomeDaLocal(ownerId, root);
        List<Long> profileIds = new ArrayList<>();
        for (JsonNode p : root.path("perfis")) {
            profileIds.add(importProfile(ownerId, p, nome));
        }
        for (JsonNode k : root.path("chavesPerfis")) {
            importProfileKey(ownerId, k);
        }
        if (root.has("smtp")) {
            JsonNode s = root.get("smtp");
            byte[] pwd = s.hasNonNull("senha") ? crypto.encrypt(Base64.getDecoder().decode(s.get("senha").asText()))
                    : null;
            jdbc.sql("""
                    insert into user_smtp_setting (owner_id, host, port, security, username, password_enc, from_address, enabled)
                    values (:o, :h, :p, :s, :u, :pw, :f, :e)
                    on conflict (owner_id) do update set host = excluded.host, port = excluded.port,
                       security = excluded.security, username = excluded.username, password_enc = excluded.password_enc,
                       from_address = excluded.from_address, enabled = excluded.enabled, updated_at = now()""")
                    .param("o", ownerId).param("h", s.path("host").asText()).param("p", s.path("porta").asInt())
                    .param("s", s.path("seguranca").asText("STARTTLS")).param("u", s.path("usuario").asString(null))
                    .param("pw", pwd).param("f", s.path("remetente").asText()).param("e", s.path("ativo").asBoolean(false))
                    .update();
        }
        audit.record("CONFIG_IMPORT", "configuracao", AuditService.SUCESSO,
                Map.of("maquinasCriadas", preview.machinesCreate().size(), "maquinasSubstituidas",
                        preview.machinesReplace().size(), "perfisCriados", preview.profilesCreate().size(),
                        "perfisSubstituidos", preview.profilesReplace().size()));
        for (long id : profileIds) {
            events.publishEvent(new ProfileService.ProfileChanged(id, false));
            events.publishEvent(new SyncService.SyncScheduleChanged(id));
        }
        return preview;
    }

    private void importMachine(long ownerId, JsonNode m) {
        String name = m.path("nome").asText();
        byte[] pwd = m.hasNonNull("senha") ? crypto.encrypt(Base64.getDecoder().decode(m.get("senha").asText())) : null;
        Map<String, Object> p = new HashMap<>();
        p.put("o", ownerId);
        p.put("n", name);
        p.put("d", m.path("descricao").asString(null));
        p.put("h", m.path("host").asText());
        p.put("pr", m.path("protocolo").asText());
        p.put("po", m.path("porta").asInt());
        p.put("f", m.path("modoFtps").asString(null));
        p.put("u", m.path("usuario").asString(null));
        p.put("a", m.path("autenticacao").asString(null));
        p.put("pw", pwd);
        p.put("c", m.path("conexoes").asInt(4));
        Long id = jdbc.sql("select id from machine where owner_id = :o and name = :n").param("o", ownerId)
                .param("n", name).query(Long.class).optional().orElse(null);
        if (id == null) {
            id = jdbc.sql("""
                    insert into machine (owner_id, name, description, host, protocol, port, ftps_mode, username, auth_type,
                           password_enc, max_connections)
                    values (:o, :n, :d, :h, :pr, :po, :f, :u, :a, :pw, :c) returning id""").params(p)
                    .query(Long.class).single();
        } else {
            p.put("id", id);
            jdbc.sql("""
                    update machine set description = :d, host = :h, protocol = :pr, port = :po, ftps_mode = :f,
                           username = :u, auth_type = :a, password_enc = :pw, max_connections = :c, updated_at = now()
                     where id = :id and owner_id = :o""").params(p).update();
        }
        jdbc.sql("delete from server_identity where machine_id = :m").param("m", id).update();
        if (m.has("identidade")) {
            jdbc.sql("""
                    insert into server_identity (machine_id, kind, fingerprint, confirmed_at)
                    values (:m, :k, :f, now())""").param("m", id).param("k", m.path("identidade").path("tipo").asText())
                    .param("f", m.path("identidade").path("impressaoDigital").asText()).update();
        }
    }

    /**
     * Importa uma chave de perfil que ainda não existe (pelo id da chave), ligada ao perfil de mesmo nome. Fica ativa
     * só se estava ativa e o perfil não tem outra chave ativa; inativa, ainda decifra os backups antigos.
     */
    private void importProfileKey(long ownerId, JsonNode k) {
        String keyId = k.path("idChave").asText();
        if (jdbc.sql("select count(*) from profile_key where key_id = :k").param("k", keyId).query(Long.class).single() > 0) {
            return;
        }
        String perfil = k.path("perfil").asText();
        Long profileId = jdbc.sql("select id from profile where owner_id = :o and lower(name) = lower(:n)")
                .param("o", ownerId).param("n", perfil).query(Long.class).optional().orElse(null);
        boolean ativa = k.path("ativa").asBoolean(false) && profileId != null
                && jdbc.sql("select count(*) from profile_key where profile_id = :p and active").param("p", profileId)
                        .query(Long.class).single() == 0;
        jdbc.sql("""
                insert into profile_key (key_id, owner_id, profile_id, profile_name, keyset_enc, active)
                values (:k, :o, :p, :n, :e, :a)""").param("k", keyId).param("o", ownerId).param("p", profileId)
                .param("n", perfil).param("e", crypto.encrypt(Base64.getDecoder().decode(k.path("chave").asText())))
                .param("a", ativa).update();
    }

    private long machineId(long ownerId, String name) {
        return jdbc.sql("select id from machine where owner_id = :o and name = :n").param("o", ownerId)
                .param("n", name).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "configuracao.maquina-ausente",
                        Map.of("maquina", name)));
    }

    /**
     * Traduz o nome da máquina LOCAL do arquivo ({@code maquinaLocal}) para o da LOCAL desta instalação, que pode ter
     * outro nome (ex.: reinstalação com outro M2M_LOCAL_NAME). Os demais nomes passam como estão.
     */
    private UnaryOperator<String> nomeDaLocal(long ownerId, JsonNode root) {
        String doArquivo = root.path("maquinaLocal").asString(null);
        String daqui = jdbc.sql("select name from machine where owner_id = :o and protocol = 'LOCAL'").param("o", ownerId)
                .query(String.class).optional().orElse(null);
        if (doArquivo == null || daqui == null || doArquivo.equals(daqui)) {
            return UnaryOperator.identity();
        }
        return n -> doArquivo.equals(n) ? daqui : n;
    }

    private long importProfile(long ownerId, JsonNode p, UnaryOperator<String> nome) {
        String name = p.path("nome").asText();
        long source = machineId(ownerId, nome.apply(p.path("origem").asText()));
        long storage = machineId(ownerId, nome.apply(p.path("armazenamento").asText()));
        Long id = jdbc.sql("select id from profile where owner_id = :o and lower(name) = lower(:n)").param("o", ownerId)
                .param("n", name).query(Long.class).optional().orElse(null);
        Map<String, Object> v = new HashMap<>();
        v.put("o", ownerId);
        v.put("n", name);
        v.put("c", p.path("complemento").asString(null));
        v.put("s", source);
        v.put("st", storage);
        v.put("sd", p.path("diretorioArmazenamento").asText());
        v.put("t", p.path("limiarSuspeito").asDouble(0.5));
        v.put("e", p.path("criptografia").asBoolean(false));
        if (id == null) {
            id = jdbc.sql("""
                    insert into profile (owner_id, name, complement, source_machine_id, storage_machine_id, storage_dir,
                           suspect_threshold, encryption_enabled)
                    values (:o, :n, :c, :s, :st, :sd, :t, :e) returning id""").params(v).query(Long.class).single();
        } else {
            v.put("id", id);
            jdbc.sql("""
                    update profile set complement = :c, source_machine_id = :s, storage_machine_id = :st,
                           storage_dir = :sd, suspect_threshold = :t, encryption_enabled = :e, updated_at = now()
                     where id = :id""").params(v).update();
            jdbc.sql("delete from profile_dir where profile_id = :p").param("p", id).update();
            jdbc.sql("delete from backup_schedule where profile_id = :p").param("p", id).update();
            jdbc.sql("delete from sync_schedule where profile_id = :p").param("p", id).update();
        }
        int pos = 1;
        for (JsonNode d : p.path("diretorios")) {
            long dirId = jdbc.sql("insert into profile_dir (profile_id, position, path) values (:p, :pos, :path) returning id")
                    .param("p", id).param("pos", pos++).param("path", d.path("caminho").asText()).query(Long.class)
                    .single();
            for (JsonNode mp : d.path("mapeamentos")) {
                jdbc.sql("insert into profile_dir_mapping (profile_dir_id, machine_id, path) values (:d, :m, :path)")
                        .param("d", dirId).param("m", machineId(ownerId, nome.apply(mp.path("maquina").asText())))
                        .param("path", mp.path("caminho").asText()).update();
            }
        }
        if (p.has("agendamentoBackup")) {
            jdbc.sql("insert into backup_schedule (profile_id, time_of_day, enabled) values (:p, cast(:t as time), :e)")
                    .param("p", id).param("t", p.path("agendamentoBackup").path("horario").asText())
                    .param("e", p.path("agendamentoBackup").path("ativo").asBoolean(true)).update();
        }
        for (JsonNode s : p.path("agendamentosSincronizacao")) {
            jdbc.sql("""
                    insert into sync_schedule (profile_id, dest_machine_id, time_of_day, enabled, max_deletions_percent,
                           max_deletions_abs) values (:p, :d, cast(:t as time), :e, :pc, :ab)""")
                    .param("p", id).param("d", machineId(ownerId, nome.apply(s.path("destino").asText())))
                    .param("t", s.path("horario").asText()).param("e", s.path("ativo").asBoolean(false))
                    .param("pc", s.path("limitePercentual").asInt(20)).param("ab", s.path("limiteAbsoluto").asInt(500))
                    .update();
        }
        return id;
    }
}
