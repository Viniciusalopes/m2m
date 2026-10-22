package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.backup.ArchiveFiles;
import br.com.vovolinux.m2m.backup.ArchiveStatus;
import br.com.vovolinux.m2m.backup.BackupArchive;
import br.com.vovolinux.m2m.backup.BackupArchiveRepository;
import br.com.vovolinux.m2m.backup.Manifest;
import br.com.vovolinux.m2m.backup.ManifestIO;
import br.com.vovolinux.m2m.crypto.ArchiveCrypto;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job REINDEX (D26): varre um diretório de qualquer máquina, cria o registro dos {@code *.tar.zst} que o banco não
 * conhece (lendo o manifesto lateral ou, sem ele, o interno) e marca como {@code MISSING} os registros sem arquivo.
 */
@Component
public class ReindexHandler implements JobHandler {

    private final MachineRepository machines;
    private final GatewayPool pool;
    private final BackupArchiveRepository archives;
    private final ProfileRepository profiles;
    private final ManifestIO manifestIO;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final ArchiveCrypto crypto;

    public ReindexHandler(MachineRepository machines, GatewayPool pool, BackupArchiveRepository archives,
            ProfileRepository profiles, ManifestIO manifestIO, JsonMapper json, JdbcClient jdbc,
            ArchiveCrypto crypto) {
        this.crypto = crypto;
        this.machines = machines;
        this.pool = pool;
        this.archives = archives;
        this.profiles = profiles;
        this.manifestIO = manifestIO;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return JobType.REINDEX;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        return Set.of(params.get("machineId").asLong());
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long machineId = ctx.params().get("machineId").asLong();
        String dir = stripSlash(ctx.params().get("dir").asText());
        Machine machine = machines.find(ctx.ownerId(), machineId)
                .orElseThrow(() -> new JobFailure("perfil.maquina-inexistente"));
        ctx.progress().step("SCANNING");
        long criados = 0;
        long jaConhecidos = 0;
        long invalidos = 0;
        Set<String> presentes = new HashSet<>();
        try (GatewayPool.Lease lease = pool.acquire(machine)) {
            FileGateway gw = lease.gateway();
            List<FileEntry> entries;
            try {
                entries = gw.list(dir);
            } catch (NoSuchFileException e) {
                throw new JobFailure("reindexacao.diretorio-inexistente", Map.of("diretorio", dir));
            }
            List<FileEntry> candidatos = entries.stream().filter(e -> !e.isDirectory() && (e.name().endsWith(".tar.zst") || e.name().endsWith(".tar.zst.enc")))
                    .toList();
            ctx.progress().totals(0, candidatos.size());
            Set<String> conhecidos = new HashSet<>();
            for (BackupArchive a : conhecidosEm(ctx.ownerId(), machineId, dir)) {
                conhecidos.add(a.fileName());
            }
            for (FileEntry e : candidatos) {
                ctx.token().throwIfCancelled();
                ctx.progress().current(e.name());
                presentes.add(e.name());
                if (conhecidos.contains(e.name())) {
                    jaConhecidos++;
                } else if (indexar(ctx, gw, machine, dir, e)) {
                    criados++;
                } else {
                    invalidos++;
                    ctx.progress().warning();
                }
                ctx.progress().itemDone();
            }
        }
        long ausentes = 0;
        for (BackupArchive a : conhecidosEm(ctx.ownerId(), machineId, dir)) {
            if (!presentes.contains(a.fileName()) && !ArchiveStatus.MISSING.equals(a.status())) {
                archives.setStatus(a.id(), ArchiveStatus.MISSING, "arquivo inexistente");
                ausentes++;
            }
        }
        ctx.result().put("created", criados);
        ctx.result().put("alreadyKnown", jaConhecidos);
        ctx.result().put("invalid", invalidos);
        ctx.result().put("missing", ausentes);
        if (invalidos > 0) {
            ctx.completeWithWarnings();
        }
    }

    private List<BackupArchive> conhecidosEm(long ownerId, long machineId, String dir) {
        return archives.inDirectory(ownerId, machineId, dir);
    }

    private boolean indexar(JobContext ctx, FileGateway gw, Machine machine, String dir, FileEntry file) {
        String path = dir + "/" + file.name();
        boolean enc = file.name().endsWith(".enc");
        AtomicLong items = new AtomicLong();
        AtomicLong files = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        Manifest.Header header = null;
        ArchiveCrypto.Key usedKey = null;
        try {
            if (!enc) {
                header = lerManifesto(gw, path, null, items, files, bytes);
            } else {
                // a chave não está no arquivo: experimenta as do usuário até uma decifrar o manifesto
                for (ArchiveCrypto.Key k : crypto.keysOf(ctx.ownerId())) {
                    try {
                        header = lerManifesto(gw, path, k, items, files, bytes);
                        usedKey = k;
                        break;
                    } catch (IOException | RuntimeException tentativa) {
                        // outra chave
                    }
                }
                if (header == null) {
                    ctx.message("WARN", "reindexacao.chave-nao-encontrada", Map.of("arquivo", file.name()));
                    return false;
                }
            }
        } catch (IOException | RuntimeException e) {
            ctx.message("WARN", "reindexacao.manifesto-ilegivel", Map.of("arquivo", file.name(), "motivo",
                    String.valueOf(e.getMessage())));
            return false;
        }
        final Manifest.Header h = header;
        var perfil = profiles.findAll(ctx.ownerId()).stream().filter(p -> p.name().equals(h.profile())).findFirst();
        String type = header.type() == null ? "REGULAR" : header.type();
        long id = archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(),
                perfil.map(ProfileRepository.Row::id).orElse(null), header.profile(), type, ArchiveStatus.COMPLETED,
                true, machine.id(), machine.name(), dir, file.name(), header.sourceMachine(), header.sourceProtocol(),
                header.fullFidelity(), items.get(), files.get(), bytes.get(), file.size(), null, enc,
                json.writeValueAsString(header.directories()),
                json.writeValueAsString(header.fidelityWarnings() == null ? List.of() : header.fidelityWarnings()), null,
                ctx.id(), null, usedKey == null ? null : usedKey.keyId()));
        try {
            jdbc.sql("update backup_archive set created_at = :c where id = :id")
                    .param("c", java.sql.Timestamp.from(Instant.parse(header.createdAt()))).param("id", id).update();
        } catch (RuntimeException ignorado) {
            // data do cabeçalho ausente ou inválida: mantém a data da reindexação
        }
        return true;
    }

    private Manifest.Header lerManifesto(FileGateway gw, String path, ArchiveCrypto.Key key, AtomicLong items,
            AtomicLong files, AtomicLong bytes) throws IOException {
        items.set(0);
        files.set(0);
        bytes.set(0);
        java.util.function.Consumer<Manifest.Item> contar = i -> {
            items.incrementAndGet();
            if ("FILE".equals(i.type())) {
                files.incrementAndGet();
                bytes.addAndGet(i.size());
            }
        };
        try (InputStream raw = gw.openRead(path + ArchiveFiles.MANIFEST_SUFFIX, 0);
                InputStream side = new ZstdInputStream(key == null ? raw : crypto.decrypt(key, raw))) {
            return manifestIO.read(side, contar);
        } catch (NoSuchFileException semLateral) {
            items.set(0);
            files.set(0);
            bytes.set(0);
        }
        try (InputStream raw = gw.openRead(path, 0);
                TarArchiveInputStream tar = new TarArchiveInputStream(
                        new ZstdInputStream(key == null ? raw : crypto.decrypt(key, raw)),
                        StandardCharsets.UTF_8.name())) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.getName().equals(Manifest.INTERNAL_ENTRY)) {
                    return manifestIO.read(tar, contar);
                }
            }
        }
        throw new IOException("Manifesto interno ausente");
    }

    private static String stripSlash(String d) {
        return d.length() > 1 && d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }
}
