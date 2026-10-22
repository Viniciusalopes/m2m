package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobFailure;
import br.com.vovolinux.m2m.job.JobHandler;
import br.com.vovolinux.m2m.job.JobProgress;
import br.com.vovolinux.m2m.job.JobType;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.machine.Protocol;
import br.com.vovolinux.m2m.profile.ProfileRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job BACKUP (D4–D7): varredura → gravação {@code .tar.zst} em streaming no armazenamento (como {@code .partial}) →
 * validação por releitura → renomeação → manifesto lateral → registro, retenção e verificação de suspeito.
 */
@Component
public class BackupHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(BackupHandler.class);

    private final ProfileRepository profiles;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final SourceScanner scanner;
    private final SourceFileReader reader;
    private final ManifestIO manifestIO;
    private final ArchiveValidator validator;
    private final BackupArchiveRepository archives;
    private final RetentionService retention;
    private final JsonMapper json;
    private final Path tmpRoot;
    private final int spoolLimit;

    public BackupHandler(ProfileRepository profiles, MachineRepository machines, GatewayPool pool,
            SourceScanner scanner, SourceFileReader reader, ManifestIO manifestIO, ArchiveValidator validator,
            BackupArchiveRepository archives, RetentionService retention, JsonMapper json,
            @Value("${m2m.tmp-dir:/var/lib/m2m-tmp}") String tmpDir,
            @Value("${m2m.backup.spool-limit:" + Spool.DEFAULT_LIMIT + "}") int spoolLimit) {
        this.profiles = profiles;
        this.machines = machines;
        this.pool = pool;
        this.scanner = scanner;
        this.reader = reader;
        this.manifestIO = manifestIO;
        this.validator = validator;
        this.archives = archives;
        this.retention = retention;
        this.json = json;
        this.tmpRoot = Path.of(tmpDir);
        this.spoolLimit = spoolLimit;
    }

    @Override
    public String type() {
        return JobType.BACKUP;
    }

    @Override
    public Set<Long> machinesOf(Job job, JsonNode params) {
        return profiles.findById(params.get("profileId").asLong())
                .map(p -> (Set<Long>) new java.util.HashSet<>(List.of(p.sourceMachineId(), p.storageMachineId())))
                .orElse(Set.of());
    }

    /** Estado de uma execução, para a limpeza em qualquer desfecho. */
    private static final class Run {
        String partialPath;
        Machine storage;
        Path tmpDir;
        String finalName;
        List<Map<String, Object>> warnings = new ArrayList<>();
        boolean finalized;
        List<String> roots = List.of();
        String archiveSha;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        long profileId = ctx.params().get("profileId").asLong();
        String complementOverride = ctx.params().hasNonNull("complement") ? ctx.params().get("complement").asText()
                : null;
        ProfileRepository.Row profile = profiles.find(ctx.ownerId(), profileId)
                .orElseThrow(() -> new JobFailure("perfil.nao-encontrado"));
        Machine source = machines.find(ctx.ownerId(), profile.sourceMachineId())
                .orElseThrow(() -> new JobFailure("perfil.origem-inexistente"));
        Machine storage = machines.find(ctx.ownerId(), profile.storageMachineId())
                .orElseThrow(() -> new JobFailure("perfil.armazenamento-inexistente"));
        List<ProfileRepository.DirRow> dirs = profiles.dirs(profileId);
        if (dirs.isEmpty()) {
            throw new JobFailure("backup.perfil-sem-diretorios");
        }

        Run run = new Run();
        run.storage = storage;
        run.tmpDir = tmpRoot.resolve("job-" + ctx.id());
        Files.createDirectories(run.tmpDir);
        String error = null;
        String outcome = ArchiveStatus.FAILED;
        try {
            Result result = execute(ctx, profile, source, storage, dirs, complementOverride, run);
            run.finalized = true;
            outcome = result.status();
            ctx.result().putAll(result.summary());
            if (!run.warnings.isEmpty() || ArchiveStatus.SUSPECT.equals(result.status())
                    || ArchiveStatus.COMPLETED_WITH_WARNINGS.equals(result.status())) {
                ctx.completeWithWarnings();
            }
        } catch (CancellationException e) {
            outcome = ArchiveStatus.INTERRUPTED;
            error = "interrompido pelo usuário";
            throw e;
        } catch (Exception e) {
            if (ctx.token().isCancelled()) {
                outcome = ArchiveStatus.INTERRUPTED;
                error = "interrompido pelo usuário";
                throw new CancellationException("interrompido");
            }
            error = e instanceof JobFailure f ? f.codigo() + " " + f.parametros() : e.getMessage();
            throw e;
        } finally {
            cleanup(run);
            if (!run.finalized) {
                recordFailure(ctx, profile, source, storage, run, outcome, error);
            }
        }
    }

    record Result(String status, Map<String, Object> summary) {
    }

    private Result execute(JobContext ctx, ProfileRepository.Row profile, Machine source, Machine storage,
            List<ProfileRepository.DirRow> dirs, String complementOverride, Run run) throws Exception {
        JobProgress progress = ctx.progress();
        String storageDir = profile.storageDir();

        progress.step("CHECK_CONNECTION");
        try (GatewayPool.Lease srcLease = pool.acquire(source);
                GatewayPool.Lease dstLease = pool.acquire(storage)) {
            FileGateway src = srcLease.gateway();
            FileGateway dst = dstLease.gateway();
            ctx.token().throwIfCancelled();
            dst.mkdirs(storageDir);

            // ---- varredura prévia (8.1) ----
            progress.step("SCANNING");
            Path scanFile = run.tmpDir.resolve("scan.ndjson");
            List<String> roots = dirs.stream().map(ProfileRepository.DirRow::path).toList();
            run.roots = roots;
            SourceScanner.Summary scan = scanner.scan(src, roots, scanFile, ctx.token(), progress::current);
            progress.current(null);
            progress.totals(scan.bytes(), scan.items());
            for (String special : scan.specials()) {
                warn(ctx, run, "backup.item-especial-ignorado", Map.of("caminho", special));
            }
            checkFreeSpace(dst, storageDir, scan.bytes());

            // ---- gravação (8.2, 8.3) ----
            progress.step("BACKING_UP");
            ZonedDateTime started = ZonedDateTime.now(BackupNaming.ZONE);
            String complement = complementOverride != null && !complementOverride.isBlank() ? complementOverride
                    : profile.complement();
            boolean encrypted = false; // criptografia por perfil entra na tarefa 15.4
            String finalName = uniqueName(dst, storageDir, BackupNaming.regular(profile.name(), complement,
                    source.name(), started, encrypted), profile, complement, source, started, encrypted);
            run.finalName = finalName;
            String partial = storageDir + "/" + BackupNaming.partial(finalName);
            run.partialPath = partial;

            Manifest.Header header = header(profile, source, dirs, started);
            Path itemsFile = run.tmpDir.resolve("items.ndjson");
            long readBytes = writeArchive(ctx, src, dst, partial, scanFile, itemsFile, header, run);

            // ---- validação por releitura (8.6) ----
            progress.step("VALIDATING");
            progress.resetCounters();
            progress.totals(readBytes, 0);
            ArchiveValidator.Result validation;
            try (InputStream in = new ProgressInputStream(dst.openRead(partial, 0), progress)) {
                validation = validator.validate(in, run.tmpDir, ctx.token());
            } catch (ArchiveValidator.ValidationFailed e) {
                throw new JobFailure("backup.validacao-falhou", Map.of("motivo", String.valueOf(e.getMessage())));
            }
            if (!validation.archiveSha256().equals(run.archiveSha)) {
                throw new JobFailure("backup.validacao-falhou", Map.of("motivo", "SHA-256 do arquivo divergente"));
            }

            // ---- finalização ----
            progress.step("FINALIZING");
            String finalPath = storageDir + "/" + finalName;
            dst.rename(partial, finalPath);
            run.partialPath = null;
            writeSideManifest(dst, finalPath, itemsFile, header);

            Map<String, Object> summary = new HashMap<>();
            summary.put("fileName", finalName);
            summary.put("items", validation.items());
            summary.put("files", validation.files());
            summary.put("bytes", validation.bytes());
            summary.put("archiveBytes", validation.archiveBytes());
            summary.put("sha256", validation.archiveSha256());

            BackupArchive previous = archives.lastAccepted(profile.id()).orElse(null);
            boolean suspect = isSuspect(previous, validation.files(), validation.bytes(), profile.suspectThreshold());
            String status;
            if (suspect) {
                status = ArchiveStatus.SUSPECT;
            } else if (run.warnings.isEmpty()) {
                status = ArchiveStatus.COMPLETED;
            } else {
                status = ArchiveStatus.COMPLETED_WITH_WARNINGS;
            }
            long id = archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profile.id(),
                    profile.name(), "REGULAR", status, !suspect, storage.id(), storage.name(), storageDir, finalName,
                    source.name(), source.protocol().name(), header.fullFidelity(), validation.items(),
                    validation.files(), validation.bytes(), validation.archiveBytes(), validation.archiveSha256(),
                    encrypted, json.writeValueAsString(header.directories()), json.writeValueAsString(run.warnings),
                    null, ctx.id(), null));
            summary.put("archiveId", id);
            summary.put("archiveStatus", status);
            if (suspect) {
                ctx.message("WARN", "backup.suspeito", Map.of("arquivo", finalName,
                        "arquivos", validation.files(), "anteriores", previous == null ? 0 : previous.fileCount()));
            } else {
                progress.step("RETENTION");
                RetentionService.Outcome r = retention.applyRegular(profile.id());
                summary.put("retentionRemoved", r.removed());
                for (Map<String, Object> f : r.failures()) {
                    ctx.message("WARN", "backup.retencao-falhou", f);
                }
            }
            return new Result(status, summary);
        }
    }

    // ---- gravação do arquivo ----

    /** @return bytes totais de conteúdo gravados (soma dos tamanhos dos arquivos) */
    private long writeArchive(JobContext ctx, FileGateway src, FileGateway dst, String partial, Path scanFile,
            Path itemsFile, Manifest.Header header, Run run) throws IOException {
        JobProgress progress = ctx.progress();
        HashingOutputStream sink = new HashingOutputStream(dst.openWrite(partial, 0));
        long[] total = {0};
        boolean ok = false;
        try (BufferedWriter items = manifestIO.openItemsWriter(itemsFile)) {
            try (TarZstWriter tar = new TarZstWriter(sink)) {
                manifestIO.forEachItemNdjson(scanFile, item -> {
                    try {
                        ctx.token().throwIfCancelled();
                        Manifest.Item written = writeItem(ctx, src, tar, item, run, progress);
                        if (written != null) {
                            manifestIO.writeItem(items, written);
                            total[0] += written.size();
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                items.flush();
                // manifesto como última entrada (8.5): primeiro em arquivo temporário para conhecer o tamanho
                Path manifestTmp = run.tmpDir.resolve("manifest.json");
                try (OutputStream mo = Files.newOutputStream(manifestTmp)) {
                    manifestIO.writeManifest(mo, header, itemsFile);
                }
                try (InputStream mi = Files.newInputStream(manifestTmp)) {
                    tar.streamed(Manifest.INTERNAL_ENTRY, Files.size(manifestTmp), System.currentTimeMillis(), mi);
                }
            }
            ok = true;
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            if (!ok) {
                try {
                    sink.close();
                } catch (IOException ignorado) {
                    // a conexão pode ter sido fechada à força (cancelamento)
                }
            }
        }
        run.archiveSha = sink.sha256();
        return total[0];
    }

    /** Escreve um item no tar e devolve o item como entrará no manifesto (com SHA-256), ou {@code null} se pulado. */
    private Manifest.Item writeItem(JobContext ctx, FileGateway src, TarZstWriter tar, Manifest.Item scanned,
            Run run, JobProgress progress) throws IOException {
        String srcPath = sourcePath(run, scanned);
        progress.current(srcPath);
        switch (scanned.type()) {
            case "DIR" -> {
                tar.directory(scanned.entryName(), scanned.mtime(), scanned.mode());
                progress.itemDone();
                return scanned;
            }
            case "SYMLINK" -> {
                tar.symlink(scanned.entryName(), scanned.link(), scanned.mtime(), scanned.mode());
                progress.itemDone();
                return scanned;
            }
            default -> {
                SourceFileReader.Result r = reader.read(src, srcPath, run.tmpDir, spoolLimit, ctx.token(),
                        progress::addBytes);
                if (r.skipped()) {
                    warn(ctx, run, "backup.arquivo-removido-durante-a-leitura", Map.of("caminho", srcPath));
                    progress.itemDone();
                    return null;
                }
                try (Spool content = r.content()) {
                    if (r.changed()) {
                        warn(ctx, run, "backup.arquivo-alterado-durante-a-leitura", Map.of("caminho", srcPath));
                    }
                    Integer mode = r.mode() != null ? r.mode() : scanned.mode();
                    tar.file(scanned.entryName(), r.size(), r.mtimeMillis(), mode, content);
                    progress.itemDone();
                    return new Manifest.Item(scanned.dir(), scanned.path(), "FILE", r.size(), r.mtimeMillis(), mode,
                            null, r.sha256());
                }
            }
        }
    }

    private static String sourcePath(Run run, Manifest.Item item) {
        String root = run.roots.get(item.dir() - 1);
        return item.path().isEmpty() ? root : root + "/" + item.path();
    }

    private void warn(JobContext ctx, Run run, String code, Map<String, Object> params) {
        ctx.message("WARN", code, params);
        Map<String, Object> w = new HashMap<>(params);
        w.put("codigo", code);
        run.warnings.add(w);
    }

    // ---- auxiliares ----

    Manifest.Header header(ProfileRepository.Row profile, Machine source, List<ProfileRepository.DirRow> dirs,
            ZonedDateTime when) {
        boolean full = source.protocol() == Protocol.LOCAL || source.protocol() == Protocol.SFTP;
        List<String> fidelityWarnings = full ? List.of() : List.of("permissions-not-preserved",
                "symlinks-not-preserved");
        List<Manifest.Directory> d = new ArrayList<>();
        for (int i = 0; i < dirs.size(); i++) {
            List<Manifest.MappingRef> maps = new ArrayList<>();
            for (ProfileRepository.MappingRow m : dirs.get(i).mappings()) {
                String name = machines.find(profile.ownerId(), m.machineId()).map(Machine::name).orElse("?");
                maps.add(new Manifest.MappingRef(m.machineId(), name, m.path()));
            }
            d.add(new Manifest.Directory(i + 1, dirs.get(i).path(), maps));
        }
        return new Manifest.Header(Manifest.FORMAT_VERSION, "REGULAR", profile.name(), source.name(),
                source.protocol().name(), full, fidelityWarnings,
                when.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), d, List.of());
    }

    private void checkFreeSpace(FileGateway dst, String dir, long needed) throws IOException {
        OptionalLong free = dst.freeSpace(dir);
        if (free.isPresent() && free.getAsLong() < needed) {
            throw new JobFailure("backup.espaco-insuficiente", Map.of("necessario", needed, "livre", free.getAsLong()));
        }
    }

    /** Duas execuções no mesmo segundo gerariam o mesmo nome: espera o próximo segundo. */
    private String uniqueName(FileGateway dst, String dir, String candidate, ProfileRepository.Row profile,
            String complement, Machine source, ZonedDateTime started, boolean encrypted) throws IOException {
        String name = candidate;
        ZonedDateTime t = started;
        for (int i = 0; i < 5; i++) {
            boolean exists = dst.lstatIfExists(dir + "/" + name).isPresent()
                    || dst.lstatIfExists(dir + "/" + BackupNaming.partial(name)).isPresent();
            if (!exists) {
                return name;
            }
            t = t.plusSeconds(1);
            name = BackupNaming.regular(profile.name(), complement, source.name(), t, encrypted);
        }
        return name;
    }

    static boolean isSuspect(BackupArchive previous, long files, long bytes, double threshold) {
        if (previous == null) {
            return false; // o primeiro backup do perfil nunca é suspeito (D25)
        }
        if (previous.fileCount() > 0 && files == 0) {
            return true;
        }
        return files < threshold * previous.fileCount() || bytes < threshold * previous.totalBytes();
    }

    private void writeSideManifest(FileGateway dst, String finalPath, Path itemsFile, Manifest.Header header)
            throws IOException {
        String partial = finalPath + ArchiveFiles.MANIFEST_SUFFIX + ".partial";
        try (OutputStream out = dst.openWrite(partial, 0);
                com.github.luben.zstd.ZstdOutputStream z = new com.github.luben.zstd.ZstdOutputStream(out,
                        TarZstWriter.ZSTD_LEVEL)) {
            manifestIO.writeManifest(z, header, itemsFile);
        }
        dst.rename(partial, finalPath + ArchiveFiles.MANIFEST_SUFFIX);
    }

    /** Remove o {@code .partial} e os temporários do job, usando uma conexão nova (as antigas podem estar fechadas). */
    private void cleanup(Run run) {
        if (run.partialPath != null && run.storage != null) {
            try (GatewayPool.Lease lease = pool.acquire(run.storage)) {
                try {
                    lease.gateway().delete(run.partialPath);
                } catch (NoSuchFileException ignorado) {
                    // nunca chegou a ser criado
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Não foi possível remover o arquivo parcial {}: {}", run.partialPath, e.getMessage());
            }
        }
        deleteTree(run.tmpDir);
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Não foi possível limpar {}: {}", dir, e.getMessage());
        }
    }

    private void recordFailure(JobContext ctx, ProfileRepository.Row profile, Machine source, Machine storage,
            Run run, String status, String error) {
        try {
            archives.insert(new BackupArchiveRepository.NewArchive(ctx.ownerId(), profile.id(), profile.name(),
                    "REGULAR", status, false, storage.id(), storage.name(), profile.storageDir(),
                    run.finalName == null ? "(não gerado)" : run.finalName, source.name(), source.protocol().name(),
                    true, 0, 0, 0, 0, null, false, "[]", json.writeValueAsString(run.warnings), error, ctx.id(), null));
        } catch (RuntimeException e) {
            log.warn("Não foi possível registrar o histórico do backup falho: {}", e.getMessage());
        }
    }

    @Override
    public void recover(Job job) {
        // Jobs interrompidos por queda: remove o .partial gravado no armazenamento, se houver.
        try {
            JsonNode p = json.readTree(job.params());
            var profile = profiles.findById(p.get("profileId").asLong());
            if (profile.isEmpty()) {
                return;
            }
            Machine storage = machines.find(job.ownerId(), profile.get().storageMachineId()).orElse(null);
            if (storage == null) {
                return;
            }
            try (GatewayPool.Lease lease = pool.acquire(storage)) {
                FileGateway gw = lease.gateway();
                for (var e : gw.list(profile.get().storageDir())) {
                    if (e.name().endsWith(".partial")
                            && e.name().startsWith(br.com.vovolinux.m2m.profile.Paths.slug(profile.get().name()) + "_")) {
                        gw.delete(e.path());
                        log.info("Arquivo parcial removido na recuperação: {}", e.path());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Recuperação do backup {}: {}", job.id(), e.getMessage());
        }
    }

    /** Fluxo que atualiza a contagem de bytes lidos (progresso da validação). */
    private static final class ProgressInputStream extends java.io.FilterInputStream {
        private final JobProgress progress;

        ProgressInputStream(InputStream in, JobProgress progress) {
            super(in);
            this.progress = progress;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                progress.addBytes(n);
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                progress.addBytes(1);
            }
            return b;
        }
    }
}
