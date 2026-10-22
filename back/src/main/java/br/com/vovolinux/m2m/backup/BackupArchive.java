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
        Instant finishedAt,
        /** Chave de criptografia (profile_key.key_id) quando o arquivo é {@code .enc}. */
        String keyId) {

    /** Sem chave: backups em claro. */
    public BackupArchive(long id, long ownerId, Long profileId, String profileNameSnapshot, String type, String status,
            boolean accepted, boolean pinned, Long storageMachineId, String storageMachineName, String storageDir,
            String fileName, String sourceMachineName, String sourceProtocol, boolean fullFidelity, long itemCount,
            long fileCount, long totalBytes, long archiveBytes, String sha256, boolean encrypted, String mapping,
            String warnings, String error, Long jobId, String destMachineName, Instant createdAt, Instant finishedAt) {
        this(id, ownerId, profileId, profileNameSnapshot, type, status, accepted, pinned, storageMachineId,
                storageMachineName, storageDir, fileName, sourceMachineName, sourceProtocol, fullFidelity, itemCount,
                fileCount, totalBytes, archiveBytes, sha256, encrypted, mapping, warnings, error, jobId,
                destMachineName, createdAt, finishedAt, null);
    }
}
