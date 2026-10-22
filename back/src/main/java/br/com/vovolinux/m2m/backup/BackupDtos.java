package br.com.vovolinux.m2m.backup;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class BackupDtos {

    private BackupDtos() {
    }

    public record BackupView(
            long id,
            Long profileId,
            String profileName,
            boolean orphan,
            String type,
            String status,
            boolean accepted,
            boolean pinned,
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
            JsonNode warnings,
            String error,
            Long jobId,
            String destMachineName,
            Instant createdAt,
            Instant finishedAt) {
    }

    public record Page(List<BackupView> items, int page, int size) {
    }

    public record TreeNode(String name, String path, String type, long size, Long mtime, Integer mode, String link) {
    }

    public record TreePage(String path, String parent, long total, List<TreeNode> items) {
    }
}
