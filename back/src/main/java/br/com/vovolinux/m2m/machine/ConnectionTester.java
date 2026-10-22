package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayFactory;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.files.local.LocalFileGateway;
import br.com.vovolinux.m2m.files.sftp.SftpFileGateway;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de conexão: alcance, autenticação, identidade, leitura, escrita (um único arquivo exclusivo, criado e
 * removido) e recursos do servidor. Nunca altera arquivos existentes (D19).
 */
@Service
public class ConnectionTester {

    public enum Status { OK, FAIL, SKIPPED, ACTION }

    public record Step(String name, Status status, String code, Map<String, Object> params) {
    }

    public record Result(
            boolean ok,
            boolean readOnly,
            List<Step> steps,
            List<String> features,
            String fingerprint) {
    }

    private final GatewayFactory factory;
    private final GatewayPool pool;
    private final MachineRepository repository;
    private final AuditService audit;
    private final JsonMapper json;

    public ConnectionTester(GatewayFactory factory, GatewayPool pool, MachineRepository repository,
            AuditService audit, JsonMapper json) {
        this.factory = factory;
        this.pool = pool;
        this.repository = repository;
        this.audit = audit;
        this.json = json;
    }

    public Result test(Machine m) {
        List<Step> steps = new ArrayList<>();
        List<String> features = new ArrayList<>();
        FileGateway gw;
        try {
            gw = factory.open(m);
        } catch (GatewayException e) {
            return failedToOpen(m, e, steps);
        } catch (IOException e) {
            steps.add(new Step("alcance", Status.FAIL, "conexao.falha", Map.of("detalhe", String.valueOf(e.getMessage()))));
            return finish(m, steps, features, false, true, null);
        }

        boolean writable = true;
        try (gw) {
            steps.add(new Step("alcance", Status.OK, null, Map.of()));
            steps.add(new Step("autenticacao", Status.OK, null, Map.of()));
            if (m.protocol() == Protocol.SFTP || m.protocol() == Protocol.FTPS) {
                steps.add(new Step("identidade", Status.OK, null, Map.of()));
            }

            String home;
            try {
                home = gw.homeDir();
                gw.list(home);
                steps.add(new Step("leitura", Status.OK, null, Map.of("diretorio", home)));
            } catch (IOException e) {
                steps.add(new Step("leitura", Status.FAIL, "leitura.falhou", Map.of("detalhe", String.valueOf(e.getMessage()))));
                steps.add(new Step("escrita", Status.SKIPPED, null, Map.of()));
                return finish(m, steps, features, false, true, null);
            }

            writable = testWrite(gw, home, steps);
            detectFeatures(gw, features);
            return finish(m, steps, features, writable, true, null);
        }
    }

    private Result failedToOpen(Machine m, GatewayException e, List<Step> steps) {
        String code = e.codigo();
        String fingerprint = e.parametros().get("fingerprint") instanceof String f ? f : null;
        if (code.startsWith("identidade.")) {
            steps.add(new Step("alcance", Status.OK, null, Map.of()));
            steps.add(new Step("autenticacao", Status.SKIPPED, null, Map.of()));
            steps.add(new Step("identidade", Status.ACTION, code, e.parametros()));
            return finish(m, steps, new ArrayList<>(), true, false, fingerprint);
        }
        if (code.equals("conexao.autenticacao-falhou")) {
            steps.add(new Step("alcance", Status.OK, null, Map.of()));
            steps.add(new Step("autenticacao", Status.FAIL, code, e.parametros()));
        } else {
            steps.add(new Step("alcance", Status.FAIL, code, e.parametros()));
        }
        return finish(m, steps, new ArrayList<>(), true, false, null);
    }

    /** Cria e remove um único arquivo exclusivo; em LOCAL procura o primeiro subdiretório onde a gravação é permitida. */
    private boolean testWrite(FileGateway gw, String home, List<Step> steps) {
        List<String> candidatos = new ArrayList<>();
        candidatos.add(home);
        if (gw instanceof LocalFileGateway) {
            try {
                gw.list(home).stream().filter(FileEntry::isDirectory).map(FileEntry::path).sorted()
                        .forEach(candidatos::add);
            } catch (IOException ignorado) {
                // sem subdiretórios legíveis: testa só a raiz
            }
        }
        IOException ultimo = null;
        for (String dir : candidatos) {
            String arquivo = (dir.endsWith("/") ? dir : dir + "/") + ".m2m-write-test-" + UUID.randomUUID();
            try {
                try (OutputStream out = gw.openWrite(arquivo, 0)) {
                    out.write('m');
                }
                gw.delete(arquivo);
                steps.add(new Step("escrita", Status.OK, null, Map.of("diretorio", dir)));
                return true;
            } catch (IOException e) {
                ultimo = e;
                try {
                    gw.lstatIfExists(arquivo).ifPresent(x -> {
                        try {
                            gw.delete(arquivo);
                        } catch (IOException ignorado) {
                            // best-effort: o arquivo é exclusivo e vazio de significado
                        }
                    });
                } catch (IOException ignorado) {
                    // idem
                }
                if (!(e instanceof AccessDeniedException) && !(e.getMessage() != null && e.getMessage().contains("ermission"))) {
                    break;
                }
            }
        }
        steps.add(new Step("escrita", Status.FAIL, "escrita.sem-permissao",
                Map.of("detalhe", String.valueOf(ultimo == null ? "" : ultimo.getMessage()))));
        return false;
    }

    private void detectFeatures(FileGateway gw, List<String> features) {
        if (gw instanceof SftpFileGateway s) {
            if (s.execSupported()) {
                features.add("exec");
            }
            if (s.statvfsSupported()) {
                features.add("statvfs");
            }
        }
        // FTP/FTPS: "mfmt" será detectado pelo gateway FTP (tarefa 15.1)
    }

    private Result finish(Machine m, List<Step> steps, List<String> features, boolean writable, boolean reachable,
            String fingerprint) {
        boolean ok = steps.stream().allMatch(s -> s.status() == Status.OK);
        boolean readOnly = steps.stream().anyMatch(x -> x.name().equals("leitura") && x.status() == Status.OK)
                && steps.stream().anyMatch(x -> x.name().equals("escrita") && x.status() == Status.FAIL);
        Result r = new Result(ok, readOnly, steps, features, fingerprint);
        persist(m, r, writable, features);
        audit.record("MACHINE_TEST", m.name(), ok ? AuditService.SUCESSO : AuditService.FALHA,
                Map.of("passos", steps.stream().map(s -> s.name() + ":" + s.status()).toList()));
        return r;
    }

    private void persist(Machine m, Result r, boolean writable, List<String> features) {
        // As capacidades só mudam quando o teste chegou até o fim; falhas de rede não apagam o que já se sabia.
        boolean conclusive = r.steps().stream().anyMatch(s -> s.name().equals("leitura") && s.status() == Status.OK);
        String caps = m.capabilitiesJson();
        if (conclusive) {
            caps = json.writeValueAsString(Map.of(
                    "remoteHash", features.contains("exec"),
                    "freeSpace", features.contains("statvfs"),
                    "writable", writable,
                    "setsMtime", m.protocol() == Protocol.LOCAL || m.protocol() == Protocol.SFTP
                            || features.contains("mfmt")));
        }
        repository.saveTest(m.ownerId(), m.id(), caps, json.writeValueAsString(r));
        pool.invalidate(m.id()); // novas conexões passam a usar as capacidades atualizadas
    }
}
