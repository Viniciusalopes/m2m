package br.com.vovolinux.m2m.transfer;

import java.time.Instant;
import java.util.UUID;

/** Linha de {@code transfer_item}: um item da fila de transferências (ou uma operação de arquivo no histórico). */
public record TransferItem(
        long id,
        long ownerId,
        UUID batchId,
        String op,
        String kind,
        String status,
        Long srcMachineId,
        String srcMachineName,
        String srcPath,
        Long dstMachineId,
        String dstMachineName,
        String dstPath,
        long size,
        long bytesDone,
        Long srcMtime,
        Long srcSizeStart,
        Integer srcMode,
        String linkTarget,
        long resumeOffset,
        String tmpPath,
        boolean appendFinal,
        String conflictAction,
        String skipReason,
        String note,
        String errorCode,
        String errorParams,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public static final String QUEUED = "QUEUED";
    public static final String TRANSFERRING = "TRANSFERRING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    public static final String SKIPPED = "SKIPPED";
    public static final String INTERRUPTED = "INTERRUPTED";

    public boolean restartable() {
        return FAILED.equals(status) || CANCELED.equals(status) || INTERRUPTED.equals(status);
    }
}
