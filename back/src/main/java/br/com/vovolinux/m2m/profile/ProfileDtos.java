package br.com.vovolinux.m2m.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record Mapping(@NotNull Long machineId, @NotBlank String path) {
    }

    public record Dir(@NotBlank String path, @Valid List<Mapping> mappings) {
    }

    public record Storage(@NotNull Long machineId, @NotBlank String path) {
    }

    public record Schedule(@NotBlank String time, boolean enabled) {
    }

    public record ProfileRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 60) String complement,
            @NotNull Long sourceMachineId,
            @NotEmpty @Valid List<Dir> directories,
            @NotNull @Valid Storage storage,
            @DecimalMin("0.01") @DecimalMax("1.00") Double suspectThreshold,
            Boolean encryptionEnabled,
            @Valid Schedule backupSchedule) {
    }

    public record DirView(long id, int position, String path, List<Mapping> mappings) {
    }

    public record ProfileView(
            long id,
            String name,
            String complement,
            long sourceMachineId,
            String sourceMachineName,
            List<DirView> directories,
            Storage storage,
            String storageMachineName,
            double suspectThreshold,
            boolean encryptionEnabled,
            Schedule backupSchedule,
            Instant nextBackupAt,
            Instant createdAt) {
    }

    public record Defaults(Long storageMachineId, String storageDir, double suspectThreshold) {
    }

    public record BackupRequest(@Size(max = 60) String complement) {
    }
}
