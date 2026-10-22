package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.MachineDtos.BrowseItem;
import br.com.vovolinux.m2m.machine.MachineDtos.BrowseResult;
import br.com.vovolinux.m2m.machine.MachineDtos.ConfirmIdentityRequest;
import br.com.vovolinux.m2m.machine.MachineDtos.MachineRequest;
import br.com.vovolinux.m2m.machine.MachineDtos.MachineView;
import br.com.vovolinux.m2m.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MachineController {

    private final MachineService service;
    private final ConnectionTester tester;
    private final GatewayPool pool;
    private final SshIdentityService sshIdentity;
    private final CurrentUser currentUser;

    public MachineController(MachineService service, ConnectionTester tester, GatewayPool pool,
            SshIdentityService sshIdentity, CurrentUser currentUser) {
        this.service = service;
        this.tester = tester;
        this.pool = pool;
        this.sshIdentity = sshIdentity;
        this.currentUser = currentUser;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @GetMapping("/machines")
    public List<MachineView> list() {
        return service.list(owner());
    }

    @GetMapping("/machines/{id}")
    public MachineView get(@PathVariable long id) {
        return service.get(owner(), id);
    }

    @PostMapping("/machines")
    @ResponseStatus(HttpStatus.CREATED)
    public MachineView create(@Valid @RequestBody MachineRequest req) {
        return service.create(owner(), req);
    }

    @PutMapping("/machines/{id}")
    public MachineView update(@PathVariable long id, @Valid @RequestBody MachineRequest req) {
        return service.update(owner(), id, req);
    }

    @DeleteMapping("/machines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(owner(), id);
    }

    @PostMapping("/machines/{id}/test")
    public ConnectionTester.Result test(@PathVariable long id) {
        return tester.test(service.require(owner(), id));
    }

    @PostMapping("/machines/{id}/identity/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmIdentity(@PathVariable long id, @Valid @RequestBody ConfirmIdentityRequest req) {
        service.confirmIdentity(owner(), id, req.fingerprint());
    }

    /** Navegação de diretórios (só pastas e arquivos listados, sem seguir links). */
    @GetMapping("/machines/{id}/browse")
    public BrowseResult browse(@PathVariable long id, @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "false") boolean onlyDirs) throws IOException {
        Machine m = service.require(owner(), id);
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            FileGateway gw = lease.gateway();
            try {
                String dir = path == null || path.isBlank() ? gw.homeDir() : path;
                List<BrowseItem> items = gw.list(dir).stream()
                        .filter(e -> !onlyDirs || e.isDirectory())
                        .sorted(Comparator.comparing((FileEntry e) -> e.type() != FileType.DIRECTORY)
                                .thenComparing(e -> e.name().toLowerCase()))
                        .map(e -> new BrowseItem(e.name(), e.path(), e.type().name(), e.size(), e.mtime(), e.mode(),
                                e.linkTarget()))
                        .toList();
                return new BrowseResult(dir, parent(dir), items);
            } catch (IOException e) {
                if (!(e instanceof java.nio.file.NoSuchFileException)
                        && !(e instanceof java.nio.file.AccessDeniedException)) {
                    lease.discard(); // pode ser conexão caída: não devolve ao pool
                }
                throw e;
            }
        }
    }

    @GetMapping("/ssh-identity")
    public SshIdentityService.PublicIdentity sshIdentity() {
        return sshIdentity.ensure(owner());
    }

    static String parent(String dir) {
        if (dir.equals("/")) {
            return null;
        }
        String d = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        int i = d.lastIndexOf('/');
        return i <= 0 ? "/" : d.substring(0, i);
    }
}
