package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.backup.BackupDtos.BackupView;
import br.com.vovolinux.m2m.backup.BackupDtos.TreePage;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService service;
    private final CurrentUser currentUser;
    private final ArchiveFiles files;
    private final GatewayPool pool;
    private final AuditService audit;

    public BackupController(BackupService service, CurrentUser currentUser, ArchiveFiles files, GatewayPool pool,
            AuditService audit) {
        this.service = service;
        this.currentUser = currentUser;
        this.files = files;
        this.pool = pool;
        this.audit = audit;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @GetMapping
    public List<BackupView> list(@RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean orphan, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(owner(), profileId, status, type, orphan, page, size);
    }

    @GetMapping("/{id}")
    public BackupView get(@PathVariable long id) {
        return service.view(service.require(owner(), id));
    }

    @PostMapping("/{id}/pin")
    public BackupView pin(@PathVariable long id) {
        return service.setPinned(owner(), id, true);
    }

    @PostMapping("/{id}/unpin")
    public BackupView unpin(@PathVariable long id) {
        return service.setPinned(owner(), id, false);
    }

    @PostMapping("/{id}/accept")
    public BackupView accept(@PathVariable long id) {
        return service.accept(owner(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(owner(), id);
    }

    @GetMapping("/{id}/tree")
    public TreePage tree(@PathVariable long id, @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size)
            throws IOException {
        return service.tree(owner(), id, path, page, size);
    }

    /** Baixa o arquivo em streaming, direto do armazenamento (com suporte a {@code Range}). */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        BackupArchive a = service.require(owner(), id);
        if (!ArchiveStatus.usable(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel", Map.of("status", a.status()));
        }
        Machine storage = files.storageOf(a)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel"));
        String path = ArchiveFiles.path(a);
        long total;
        try (GatewayPool.Lease lease = pool.acquire(storage)) {
            total = lease.gateway().lstat(path).size();
        }
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String[] r = range.substring(6).split("-", -1);
            try {
                if (!r[0].isEmpty()) {
                    start = Long.parseLong(r[0]);
                    if (!r[1].isEmpty()) {
                        end = Math.min(Long.parseLong(r[1]), total - 1);
                    }
                } else if (!r[1].isEmpty()) {
                    start = Math.max(0, total - Long.parseLong(r[1]));
                }
                partial = true;
            } catch (NumberFormatException e) {
                partial = false;
            }
            if (partial && (start > end || start >= total)) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
            }
        }
        final long from = start;
        final long length = end - start + 1;
        audit.record("BACKUP_DOWNLOAD", a.fileName(), AuditService.SUCESSO, Map.of("id", id, "inicio", from));
        StreamingResponseBody body = out -> {
            try (GatewayPool.Lease lease = pool.acquire(storage)) {
                FileGateway gw = lease.gateway();
                try (InputStream in = gw.openRead(path, from)) {
                    byte[] buf = new byte[1 << 16];
                    long left = length;
                    int n;
                    while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } catch (IOException e) {
                    lease.discard();
                    throw e;
                }
            }
        };
        ResponseEntity.BodyBuilder b = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.fileName() + "\"");
        if (partial) {
            b.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        return b.body(body);
    }
}
