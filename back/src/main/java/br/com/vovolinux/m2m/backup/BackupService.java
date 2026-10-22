package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.audit.AuditService;
import br.com.vovolinux.m2m.backup.BackupDtos.BackupView;
import br.com.vovolinux.m2m.backup.BackupDtos.TreeNode;
import br.com.vovolinux.m2m.backup.BackupDtos.TreePage;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.web.ApiException;
import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Gestão de backups: listar, fixar, aceitar suspeito, excluir, navegar no conteúdo (D26). */
@Service
public class BackupService {

    private final BackupArchiveRepository repository;
    private final ArchiveFiles files;
    private final GatewayPool pool;
    private final ManifestIO manifestIO;
    private final RetentionService retention;
    private final AuditService audit;
    private final JsonMapper json;

    public BackupService(BackupArchiveRepository repository, ArchiveFiles files, GatewayPool pool,
            ManifestIO manifestIO, RetentionService retention, AuditService audit, JsonMapper json) {
        this.repository = repository;
        this.files = files;
        this.pool = pool;
        this.manifestIO = manifestIO;
        this.retention = retention;
        this.audit = audit;
        this.json = json;
    }

    public BackupView view(BackupArchive a) {
        return new BackupView(a.id(), a.profileId(), a.profileNameSnapshot(), a.profileId() == null, a.type(),
                a.status(), a.accepted(), a.pinned(), a.storageMachineName(), a.storageDir(), a.fileName(),
                a.sourceMachineName(), a.sourceProtocol(), a.fullFidelity(), a.itemCount(), a.fileCount(),
                a.totalBytes(), a.archiveBytes(), a.sha256(), a.encrypted(),
                a.warnings() == null ? null : json.readTree(a.warnings()), a.error(), a.jobId(), a.destMachineName(),
                a.createdAt(), a.finishedAt());
    }

    public List<BackupView> list(long ownerId, Long profileId, String status, String type, Boolean orphan, int page,
            int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return repository.list(ownerId, profileId, status, type, orphan, limit, Math.max(page, 0) * limit).stream()
                .map(this::view).toList();
    }

    public BackupArchive require(long ownerId, long id) {
        return repository.find(ownerId, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "backup.nao-encontrado"));
    }

    public BackupView setPinned(long ownerId, long id, boolean pinned) {
        BackupArchive a = require(ownerId, id);
        repository.setPinned(id, pinned);
        audit.record(pinned ? "BACKUP_PIN" : "BACKUP_UNPIN", a.fileName(), AuditService.SUCESSO, Map.of("id", id));
        return view(require(ownerId, id));
    }

    /** O usuário aceita um backup suspeito: passa a valer para a retenção, que é aplicada (D25). */
    public BackupView accept(long ownerId, long id) {
        BackupArchive a = require(ownerId, id);
        if (!ArchiveStatus.SUSPECT.equals(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.nao-esta-suspeito");
        }
        boolean withWarnings = a.warnings() != null && json.readTree(a.warnings()).size() > 0;
        repository.setAccepted(id, true,
                withWarnings ? ArchiveStatus.COMPLETED_WITH_WARNINGS : ArchiveStatus.COMPLETED);
        audit.record("BACKUP_ACCEPT", a.fileName(), AuditService.SUCESSO, Map.of("id", id));
        if (a.profileId() != null) {
            retention.applyRegular(a.profileId());
        }
        return repository.find(ownerId, id).map(this::view).orElse(null); // pode ter sido... mantido: é o mais novo
    }

    public void delete(long ownerId, long id) {
        BackupArchive a = require(ownerId, id);
        if (repository.inUseByActiveJob(id)) {
            audit.record("BACKUP_DELETE", a.fileName(), AuditService.FALHA, Map.of("motivo", "em-uso"));
            throw new ApiException(HttpStatus.CONFLICT, "backup.em-uso");
        }
        boolean hasFile = ArchiveStatus.usable(a.status()) || ArchiveStatus.CORRUPTED.equals(a.status());
        if (hasFile) {
            try {
                files.delete(a);
            } catch (IOException e) {
                audit.record("BACKUP_DELETE", a.fileName(), AuditService.FALHA, Map.of("motivo", String.valueOf(e.getMessage())));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "backup.falha-ao-excluir",
                        Map.of("arquivo", a.fileName(), "detalhe", String.valueOf(e.getMessage())));
            }
        }
        repository.delete(id);
        audit.record("BACKUP_DELETE", a.fileName(), AuditService.SUCESSO, Map.of("id", id, "tipo", a.type()));
    }

    // ---- conteúdo ----

    /**
     * Árvore do backup, um diretório por vez, a partir do manifesto lateral (sem descompactar o arquivo).
     * {@code path} vazio lista os diretórios de origem ({@code dir-1}, {@code dir-2}...).
     */
    public TreePage tree(long ownerId, long id, String path, int page, int size) throws IOException {
        BackupArchive a = require(ownerId, id);
        if (!ArchiveStatus.usable(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel", Map.of("status", a.status()));
        }
        String virtual = path == null ? "" : path.replaceAll("^/+|/+$", "");
        List<TreeNode> children = new ArrayList<>();
        Machine storage = files.storageOf(a)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "backup.arquivo-indisponivel"));
        try (GatewayPool.Lease lease = pool.acquire(storage)) {
            FileGateway gw = lease.gateway();
            Manifest.Header header;
            try (InputStream in = new ZstdInputStream(files.openSideManifest(gw, a))) {
                String dirPrefix;
                int wantedDir;
                if (virtual.isEmpty()) {
                    header = manifestIO.readHeaderOnly(in);
                    for (Manifest.Directory d : header.directories()) {
                        children.add(new TreeNode("dir-" + d.index(), "dir-" + d.index(), "DIR", 0, null, null,
                                d.path()));
                    }
                } else {
                    int slash = virtual.indexOf('/');
                    String first = slash < 0 ? virtual : virtual.substring(0, slash);
                    wantedDir = parseDir(first);
                    dirPrefix = slash < 0 ? "" : virtual.substring(slash + 1);
                    final int dirIdx = wantedDir;
                    final String prefix = dirPrefix;
                    manifestIO.read(in, item -> {
                        if (item.dir() != dirIdx || item.path().isEmpty()) {
                            return;
                        }
                        int cut = item.path().lastIndexOf('/');
                        String parent = cut < 0 ? "" : item.path().substring(0, cut);
                        if (parent.equals(prefix)) {
                            String name = cut < 0 ? item.path() : item.path().substring(cut + 1);
                            children.add(new TreeNode(name, "dir-" + dirIdx + "/" + item.path(), item.type(),
                                    item.size(), item.mtime(), item.mode(), item.link()));
                        }
                    });
                }
            } catch (NoSuchFileException e) {
                throw new ApiException(HttpStatus.CONFLICT, "backup.manifesto-indisponivel");
            }
        }
        children.sort(Comparator.comparing((TreeNode n) -> !n.type().equals("DIR")).thenComparing(TreeNode::name));
        int limit = Math.min(Math.max(size, 1), 1000);
        int from = Math.min(Math.max(page, 0) * limit, children.size());
        List<TreeNode> pageItems = children.subList(from, Math.min(from + limit, children.size()));
        String parent = virtual.isEmpty() ? null : (virtual.contains("/") ? virtual.substring(0, virtual.lastIndexOf('/')) : "");
        return new TreePage(virtual, parent, children.size(), pageItems);
    }

    private static int parseDir(String s) {
        try {
            if (!s.startsWith("dir-")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(s.substring(4));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "backup.caminho-invalido", Map.of("caminho", s));
        }
    }

}
