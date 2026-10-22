package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.crypto.CryptoService;
import br.com.vovolinux.m2m.files.GatewayFactory;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.MachineDtos.IdentityView;
import br.com.vovolinux.m2m.machine.MachineDtos.MachineRequest;
import br.com.vovolinux.m2m.machine.MachineDtos.MachineView;
import br.com.vovolinux.m2m.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MachineService {

    private final MachineRepository repository;
    private final ServerIdentityService identities;
    private final GatewayFactory factory;
    private final GatewayPool pool;
    private final CryptoService crypto;
    private final LocalProperties local;
    private final AuditService audit;
    private final List<MachineUsageSource> usageSources;

    public MachineService(MachineRepository repository, ServerIdentityService identities, GatewayFactory factory,
            GatewayPool pool, CryptoService crypto, LocalProperties local, AuditService audit,
            List<MachineUsageSource> usageSources) {
        this.repository = repository;
        this.identities = identities;
        this.factory = factory;
        this.pool = pool;
        this.crypto = crypto;
        this.local = local;
        this.audit = audit;
        this.usageSources = usageSources;
    }

    public List<MachineView> list(long ownerId) {
        return repository.findAll(ownerId).stream().map(this::view).toList();
    }

    public Machine require(long ownerId, long id) {
        return repository.find(ownerId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "maquina.nao-encontrada"));
    }

    public MachineView get(long ownerId, long id) {
        return view(require(ownerId, id));
    }

    public MachineView create(long ownerId, MachineRequest req) {
        Machine m = build(ownerId, 0, req, null);
        long id;
        try {
            id = repository.insert(m);
        } catch (DuplicateKeyException e) {
            throw duplicate(m);
        }
        audit.record("MACHINE_CREATE", m.name(), AuditService.SUCESSO,
                Map.of("protocolo", m.protocol().name(), "host", String.valueOf(m.host())));
        return view(require(ownerId, id));
    }

    public MachineView update(long ownerId, long id, MachineRequest req) {
        Machine atual = require(ownerId, id);
        if (req.protocol() != atual.protocol()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.protocolo-imutavel");
        }
        Machine m = build(ownerId, id, req, atual);
        try {
            repository.update(m);
        } catch (DuplicateKeyException e) {
            throw duplicate(m);
        }
        pool.invalidate(id);
        boolean mudouDestino = !Objects.equals(atual.host(), m.host()) || atual.port() != m.port();
        if (mudouDestino) {
            identities.forget(id); // outro servidor: a identidade precisa ser confirmada de novo
        }
        audit.record("MACHINE_UPDATE", m.name(), AuditService.SUCESSO, Map.of("identidadeReiniciada", mudouDestino));
        return view(require(ownerId, id));
    }

    public void delete(long ownerId, long id) {
        Machine m = require(ownerId, id);
        List<String> usos = new ArrayList<>();
        for (MachineUsageSource s : usageSources) {
            usos.addAll(s.usagesOf(ownerId, id));
        }
        if (!usos.isEmpty()) {
            audit.record("MACHINE_DELETE", m.name(), AuditService.FALHA, Map.of("motivo", "em-uso"));
            throw new ApiException(HttpStatus.CONFLICT, "maquina.em-uso", Map.of("usos", usos));
        }
        pool.invalidate(id);
        repository.delete(ownerId, id);
        audit.record("MACHINE_DELETE", m.name(), AuditService.SUCESSO, Map.of());
    }

    private ApiException duplicate(Machine m) {
        if (m.protocol() == Protocol.LOCAL) {
            return new ApiException(HttpStatus.CONFLICT, "maquina.local-ja-existe");
        }
        return new ApiException(HttpStatus.CONFLICT, "maquina.nome-duplicado", Map.of("nome", m.name()));
    }

    private Machine build(long ownerId, long id, MachineRequest req, Machine atual) {
        String name = req.name().trim();
        if (repository.nameExists(ownerId, name, id == 0 ? null : id)) {
            throw new ApiException(HttpStatus.CONFLICT, "maquina.nome-duplicado", Map.of("nome", name));
        }
        Protocol protocol = req.protocol();
        int max = req.maxConnections() == null ? (atual == null ? 4 : atual.maxConnections()) : req.maxConnections();
        String desc = blankToNull(req.description());

        if (protocol == Protocol.LOCAL) {
            if (!local.enabled()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.local-desativado");
            }
            if (atual == null && repository.localExists()) {
                throw new ApiException(HttpStatus.CONFLICT, "maquina.local-ja-existe");
            }
            return new Machine(id, ownerId, name, desc, "localhost", Protocol.LOCAL, 0, null, null, null, null, max,
                    atual == null ? "{}" : atual.capabilitiesJson(), null, null);
        }

        String host = blankToNull(req.host());
        String user = blankToNull(req.username());
        if (host == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.host-obrigatorio");
        }
        if (user == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.usuario-obrigatorio");
        }
        String ftpsMode = protocol == Protocol.FTPS ? (req.ftpsMode() == null ? "EXPLICIT" : req.ftpsMode()) : null;
        if (ftpsMode != null && !ftpsMode.equals("EXPLICIT") && !ftpsMode.equals("IMPLICIT")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.modo-ftps-invalido");
        }
        int port = req.port() == null ? protocol.defaultPort(ftpsMode) : req.port();

        String authType = protocol == Protocol.SFTP ? (req.authType() == null ? "KEY" : req.authType()) : "PASSWORD";
        if (!authType.equals("KEY") && !authType.equals("PASSWORD")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.autenticacao-invalida");
        }
        byte[] passwordEnc = atual == null ? null : atual.passwordEnc();
        if (authType.equals("KEY")) {
            passwordEnc = null;
        } else if (req.password() != null && !req.password().isEmpty()) {
            passwordEnc = crypto.encrypt(req.password());
        } else if (passwordEnc == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maquina.senha-obrigatoria");
        }
        return new Machine(id, ownerId, name, desc, host, protocol, port, ftpsMode, user, authType, passwordEnc, max,
                atual == null ? "{}" : atual.capabilitiesJson(), null, null);
    }

    public MachineView view(Machine m) {
        var caps = factory.capabilitiesOf(m);
        List<String> warnings = new ArrayList<>();
        if (m.protocol() == Protocol.FTP) {
            warnings.add("ftp.sem-tls");
        }
        if (!Fidelity.of(m.protocol(), caps).full()) {
            warnings.add("fidelidade.parcial");
        }
        IdentityView identity = null;
        if (m.protocol() == Protocol.SFTP || m.protocol() == Protocol.FTPS) {
            identity = identities.find(m.id())
                    .map(i -> new IdentityView(i.kind(), i.confirmed(), i.fingerprint(), i.pendingFingerprint(),
                            i.confirmedAt()))
                    .orElse(new IdentityView(m.protocol() == Protocol.SFTP ? "SSH" : "TLS", false, null, null, null));
        }
        return new MachineView(m.id(), m.name(), m.description(), m.host(), m.protocol(), m.port(), m.ftpsMode(),
                m.username(), m.authType(), m.passwordEnc() != null, m.maxConnections(), caps,
                Fidelity.of(m.protocol(), caps), warnings, identity, m.lastTestAt(), m.lastTestJson());
    }

    public void confirmIdentity(long ownerId, long id, String fingerprint) {
        Machine m = require(ownerId, id);
        identities.confirm(id, m.name(), fingerprint);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
