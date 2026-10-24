package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.config.AppProperties;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobRepository;
import br.com.vovolinux.m2m.job.JobService;
import br.com.vovolinux.m2m.job.JobStatus;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.user.UserRepository;
import br.com.vovolinux.m2m.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operações do instalador para o arquivo de reinstalação: exportar e importar a configuração e reindexar um diretório
 * de backups. Só atende de dentro do container do back ({@code docker exec m2m-back-java curl http://127.0.0.1:8080/...})
 * e com o token derivado da chave mestra, que o instalador calcula a partir do {@code .env}; não passa pelo front.
 */
@RestController
@RequestMapping("/internal/instalador")
public class InstallerController {

    /** Mensagem do HMAC-SHA256 que gera o token, com a chave mestra como chave. */
    static final String MENSAGEM_TOKEN = "m2m-instalador";
    private static final Duration PRAZO_REINDEXACAO = Duration.ofMinutes(30);
    private static final Set<String> TERMINAIS = Set.of(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS,
            JobStatus.FAILED, JobStatus.INTERRUPTED, JobStatus.CANCELED);

    /** Corpo de todas as operações; cada uma usa só os campos que precisa. */
    public record Pedido(String token, String usuario, String senha, String conteudo, String maquina,
            String diretorio) {
    }

    public record Exportado(String conteudo) {
    }

    public record Reindexado(String status, JsonNode resultado) {
    }

    private final ConfigTransferService transfer;
    private final UserRepository users;
    private final MachineRepository machines;
    private final JobService jobs;
    private final JobRepository jobRepository;
    private final JsonMapper json;
    private final byte[] token;

    public InstallerController(ConfigTransferService transfer, UserRepository users, MachineRepository machines,
            JobService jobs, JobRepository jobRepository, JsonMapper json, AppProperties props) {
        this.transfer = transfer;
        this.users = users;
        this.machines = machines;
        this.jobs = jobs;
        this.jobRepository = jobRepository;
        this.json = json;
        this.token = token(props.secretKey()).getBytes(StandardCharsets.US_ASCII);
    }

    /** Token esperado: HMAC-SHA256 de {@link #MENSAGEM_TOKEN} com a chave mestra, em hexadecimal. */
    static String token(String chaveMestra) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chaveMestra.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(MENSAGEM_TOKEN.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @PostMapping("/config/export")
    public Exportado exportar(@RequestBody Pedido p, HttpServletRequest req) {
        return new Exportado(transfer.export(dono(p, req), p.senha()));
    }

    @PostMapping("/config/import")
    public ConfigTransferService.Preview importar(@RequestBody Pedido p, HttpServletRequest req) {
        return transfer.apply(dono(p, req), p.conteudo(), p.senha());
    }

    /** Reindexa um diretório de uma máquina (pelo nome) e espera o job terminar, devolvendo o resultado. */
    @PostMapping("/reindex")
    public Reindexado reindexar(@RequestBody Pedido p, HttpServletRequest req) throws InterruptedException {
        long dono = dono(p, req);
        if (p.maquina() == null || p.diretorio() == null || !p.diretorio().startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reindexacao.parametros-invalidos");
        }
        long maquina = machines.findAll(dono).stream().filter(m -> m.name().equals(p.maquina())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "perfil.maquina-inexistente",
                        Map.of("maquina", p.maquina())))
                .id();
        Map<String, Object> params = new HashMap<>();
        params.put("machineId", maquina);
        params.put("dir", p.diretorio());
        Job job = jobs.enqueue(dono, JobType.REINDEX, "SYSTEM", null, null, params);
        Instant limite = Instant.now().plus(PRAZO_REINDEXACAO);
        while (Instant.now().isBefore(limite)) {
            Job atual = jobRepository.findAny(job.id()).orElseThrow();
            if (TERMINAIS.contains(atual.status())) {
                return new Reindexado(atual.status(), atual.result() == null ? null : json.readTree(atual.result()));
            }
            Thread.sleep(1000);
        }
        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "reindexacao.prazo-esgotado", Map.of("job", job.id()));
    }

    /** Confere a origem (loopback do container) e o token, e devolve o id do usuário dono da configuração. */
    private long dono(Pedido p, HttpServletRequest req) {
        if (!loopback(req.getRemoteAddr()) || p.token() == null
                || !MessageDigest.isEqual(token, p.token().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "instalador.acesso-negado");
        }
        return users.findByUsername(String.valueOf(p.usuario()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "instalador.usuario-inexistente",
                        Map.of("usuario", String.valueOf(p.usuario()))))
                .id();
    }

    private static boolean loopback(String endereco) {
        try {
            return endereco != null && InetAddress.getByName(endereco).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
