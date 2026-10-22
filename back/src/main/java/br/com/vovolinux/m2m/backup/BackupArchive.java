package br.com.vovolinux.m2m.backup;

import java.time.Instant;

/** Linha de {@code backup_archive}; {@code mapping} e {@code warnings} são JSON em texto. */
public record BackupArchive(
        long id,
        long ownerId,
        Long profileId,
        String profileNameSnapshot,
        String type,
        String status,
        boolean accepted,
        boolean pinned,
        Long storageMachineId,
        String storageMachineName,
        String storageDir,
        String fileName,
        String sourceMachineName,
        String sourceProtocol,
        boolean fullFidelity,
        long itemCount,
        long fileCount,
        long totalBytes,
        long archiveBytes,
        String sha256,
        boolean encrypted,
        String mapping,
        String warnings,
        String error,
        Long jobId,
        String destMachineName,
        Instant createdAt,
        Instant finishedAt) {
}
