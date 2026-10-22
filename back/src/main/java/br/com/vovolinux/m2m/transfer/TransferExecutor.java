package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.files.Capabilities;
import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.files.FsOps;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.ProgressBus;
import br.com.vovolinux.m2m.job.RabbitTopology;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor da fila {@code m2m.transfers} (D22): N consumidores (TRANSFER_CONCURRENCY), um item por mensagem. Cada
 * arquivo é lido do gateway de origem e gravado, em streaming, como {@code .<nome>.m2m-tmp} no gateway de destino e
 * só então renomeado: remoto↔remoto passa pelo host sem tocar disco (buffer de 1 MiB).
 */
@Component
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);
    static final int BUFFER = 1 << 20;
    private static final long PROGRESS_EVERY_MS = 500;
    private static final long PERSIST_EVERY_MS = 2000;

    /** Resultado de um item que terminou sem erro. */
    private record Done(String status, String skipReason, String note) {
    }

    private final TransferRepository repository;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final RunningTransfers running;
    private final ProgressBus bus;
    private final JsonMapper json;

    public TransferExecutor(TransferRepository repository, MachineRepository machines, GatewayPool pool,
            RunningTransfers running, ProgressBus bus, JsonMapper json) {
        this.repository = repository;
        this.machines = machines;
        this.pool = pool;
        this.running = running;
        this.bus = bus;
        this.json = json;
    }

    @RabbitListener(queues = RabbitTopology.TRANSFERS,
            concurrency = "${m2m.transfers.concurrency:2}-${m2m.transfers.concurrency:2}")
    public void onMessage(String body) {
        long id;
        try {
            id = Long.parseLong(body.trim());
        } catch (NumberFormatException e) {
            log.warn("Mensagem inválida na fila de transferências: {}", body);
            return;
        }
        Optional<TransferItem> claimed = repository.claim(id);
        if (claimed.isEmpty()) {
            return; // cancelado na fila ou duplicado
        }
        run(claimed.get());
    }

    void run(TransferItem item) {
        RunningTransfers.Handle handle = running.register(item.id(), item.resumeOffset());
        publish(item, TransferItem.TRANSFERRING, item.resumeOffset(), 0);
        String status;
        String skipReason = null;
        String note = null;
        String errorCode = null;
        Map<String, Object> errorParams = null;
        try {
            Done d = execute(item, handle);
            status = d.status();
            skipReason = d.skipReason();
            note = d.note();
        } catch (CancellationException e) {
            status = TransferItem.CANCELED;
        } catch (Throwable e) {
            if (handle.cancelled()) {
                // fechar os fluxos para cancelar faz leitura e gravação falharem: é o cancelamento em ação
                status = TransferItem.CANCELED;
            } else {
                status = TransferItem.FAILED;
                if (e instanceof GatewayException g) {
                    errorCode = g.codigo();
                    errorParams = g.parametros();
                } else if (e instanceof TransferFailure f) {
                    errorCode = f.codigo();
                    errorParams = f.parametros();
                } else if (e instanceof NoSuchFileException) {
                    errorCode = "transferencia.origem-removida";
                    errorParams = Map.of("caminho", String.valueOf(e.getMessage()));
                } else {
                    errorCode = "transferencia.falha";
                    errorParams = Map.of("detalhe", String.valueOf(e.getMessage()));
                    log.warn("Transferência {} falhou", item.id(), e);
                }
            }
        } finally {
            running.unregister(item.id());
        }
        long written = handle.bytes;
        boolean completed = TransferItem.COMPLETED.equals(status);
        try {
            repository.finish(item.id(), status, completed ? Math.max(written, item.size()) : written,
                    completed ? 0 : written, skipReason, errorCode,
                    errorParams == null ? null : json.writeValueAsString(errorParams), note);
            if (completed || TransferItem.SKIPPED.equals(status)) {
                repository.clearTmp(item.id());
            }
        } catch (RuntimeException e) {
            log.error("Não foi possível gravar o fim da transferência {}", item.id(), e);
        }
        publish(item, status, written, 0);
        try {
            if (repository.pendingInBatch(item.batchId()) == 0) {
                finalizeDirectories(item.batchId());
            }
        } catch (RuntimeException e) {
            log.debug("Ajuste final dos diretórios do lote {} adiado: {}", item.batchId(), e.getMessage());
        }
    }

    private Machine machine(long ownerId, Long id) {
        return id == null ? null : machines.find(ownerId, id).orElse(null);
    }

    private Done execute(TransferItem item, RunningTransfers.Handle h) throws IOException {
        Machine srcM = machine(item.ownerId(), item.srcMachineId());
        Machine dstM = machine(item.ownerId(), item.dstMachineId());
        if (srcM == null || dstM == null) {
            throw new TransferFailure("transferencia.maquina-removida");
        }
        try (GatewayPool.Lease sl = pool.acquire(srcM); GatewayPool.Lease dl = pool.acquire(dstM)) {
            FileGateway src = sl.gateway();
            FileGateway dst = dl.gateway();
            Capabilities caps = dst.capabilities();
            try {
                return switch (item.kind()) {
                    case "DIR" -> {
                        dst.mkdirs(item.dstPath());
                        yield new Done(TransferItem.COMPLETED, null, null);
                    }
                    case "SYMLINK" -> copyLink(item, dst, caps);
                    default -> copyFile(item, src, dst, caps, h);
                };
            } catch (IOException e) {
                // a conexão pode estar num estado inconsistente: não volta ao pool
                sl.discard();
                dl.discard();
                throw e;
            }
        }
    }

    private Done copyLink(TransferItem item, FileGateway dst, Capabilities caps) throws IOException {
        if (!caps.preservesLinks()) {
            return new Done(TransferItem.SKIPPED, "destino-sem-links", null);
        }
        dst.mkdirs(FsOps.parentOf(item.dstPath()));
        Optional<FileEntry> existing = dst.lstatIfExists(item.dstPath());
        if (existing.isPresent()) {
            FsOps.deleteTree(dst, item.dstPath());
        }
        dst.symlink(item.dstPath(), item.linkTarget());
        return new Done(TransferItem.COMPLETED, null, null);
    }

    private Done copyFile(TransferItem item, FileGateway src, FileGateway dst, Capabilities caps,
            RunningTransfers.Handle h) throws IOException {
        FileEntry se = src.lstat(item.srcPath());
        if (se.type() != FileType.FILE) {
            throw new TransferFailure("transferencia.origem-alterada", Map.of("caminho", item.srcPath()));
        }
        long srcMtime = se.mtime().toEpochMilli();
        String finalPath = item.dstPath();
        String parent = FsOps.parentOf(finalPath);
        dst.mkdirs(parent);

        long start = 0;
        String note = null;
        String tmp = parent + "/." + FsOps.nameOf(finalPath) + ".m2m-tmp";
        String target = tmp;
        boolean direct = false;
        if (item.appendFinal()) {
            // conflito "retomar": continua o arquivo final existente, se for menor que a origem
            Optional<FileEntry> existing = dst.lstatIfExists(finalPath);
            if (existing.isPresent() && existing.get().type() == FileType.FILE && existing.get().size() > 0
                    && existing.get().size() < se.size()) {
                start = existing.get().size();
                target = finalPath;
                direct = true;
            }
        } else if (item.srcSizeStart() != null) {
            // nova tentativa de um item que já começou: retoma só se a origem não mudou e o temporário existe
            if (se.size() != item.srcSizeStart() || srcMtime != item.srcMtime()) {
                note = "retomada-recusada-origem-alterada";
            } else {
                Optional<FileEntry> partial = dst.lstatIfExists(tmp);
                if (partial.isPresent() && partial.get().type() == FileType.FILE && partial.get().size() > 0
                        && partial.get().size() <= se.size()) {
                    start = partial.get().size();
                    note = "retomado";
                } else if (item.resumeOffset() > 0) {
                    note = "retomada-recusada-temporario-ausente";
                }
            }
        }
        if (start > 0 && !src.supportsResume()) {
            // origem FTP sem REST: não dá para abrir a leitura no deslocamento; recomeça pelo temporário
            start = 0;
            direct = false;
            target = tmp;
            note = "retomada-recusada-servidor-sem-suporte";
        }
        repository.markStarted(item.id(), se.size(), srcMtime, direct ? null : tmp, start, note);
        h.bytes = start;

        long lastEvent = 0;
        long lastPersist = System.currentTimeMillis();
        try (InputStream in = src.openRead(item.srcPath(), start);
                OutputStream out = dst.openWrite(target, start)) {
            h.streams(in, out);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (h.cancelled()) {
                    throw new CancellationException("cancelado");
                }
                out.write(buf, 0, n);
                h.bytes += n;
                long now = System.currentTimeMillis();
                if (now - lastEvent >= PROGRESS_EVERY_MS) {
                    lastEvent = now;
                    publish(item, TransferItem.TRANSFERRING, h.bytes, h.bytesPerSecond());
                }
                if (now - lastPersist >= PERSIST_EVERY_MS) {
                    lastPersist = now;
                    repository.saveProgress(item.id(), h.bytes);
                }
            }
        }
        if (h.cancelled()) {
            throw new CancellationException("cancelado");
        }
        FileEntry written = dst.lstat(target);
        if (written.size() != se.size()) {
            throw new TransferFailure("transferencia.tamanho-divergente",
                    Map.of("esperado", se.size(), "gravado", written.size()));
        }
        FileEntry after = src.lstat(item.srcPath());
        if (after.size() != se.size() || after.mtime().toEpochMilli() != srcMtime) {
            if (!direct) {
                dst.delete(tmp);
                repository.clearTmp(item.id());
            }
            throw new TransferFailure("transferencia.origem-alterada", Map.of("caminho", item.srcPath()));
        }
        if (caps.preservesMode() && se.mode() != null) {
            dst.setMode(target, se.mode());
        }
        if (caps.setsMtime()) {
            dst.setMtime(target, Instant.ofEpochMilli(srcMtime));
        }
        if (!direct) {
            dst.rename(tmp, finalPath);
        }
        return new Done(TransferItem.COMPLETED, null, note);
    }

    /** Terminado o lote, devolve aos diretórios criados as permissões e datas da origem (os filhos já os alteraram). */
    void finalizeDirectories(UUID batch) {
        for (TransferItem d : repository.batchDirs(batch)) {
            Machine m = machine(d.ownerId(), d.dstMachineId());
            if (m == null) {
                continue;
            }
            try (GatewayPool.Lease lease = pool.acquire(m)) {
                FileGateway gw = lease.gateway();
                if (gw.capabilities().preservesMode() && d.srcMode() != null) {
                    gw.setMode(d.dstPath(), d.srcMode());
                }
                if (gw.capabilities().setsMtime() && d.srcMtime() != null) {
                    gw.setMtime(d.dstPath(), Instant.ofEpochMilli(d.srcMtime()));
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Não foi possível ajustar o diretório {}: {}", d.dstPath(), e.getMessage());
            }
        }
    }

    void publish(TransferItem item, String status, long bytesDone, long speed) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", "item");
        e.put("id", item.id());
        e.put("batchId", item.batchId().toString());
        e.put("status", status);
        e.put("bytesDone", bytesDone);
        e.put("size", item.size());
        e.put("speed", speed);
        bus.publish("transfer." + item.ownerId(), e);
    }
}
