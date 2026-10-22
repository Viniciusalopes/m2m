package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.files.Capabilities;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class MachineDtos {

    private MachineDtos() {
    }

    /** Criação e edição. {@code password} vazio na edição mantém a senha salva. */
    public record MachineRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Size(max = 255) String host,
            @NotNull Protocol protocol,
            @Min(1) @Max(65535) Integer port,
            String ftpsMode,
            @Size(max = 100) String username,
            String authType,
            @Size(max = 500) String password,
            @Min(1) @Max(32) Integer maxConnections) {
    }

    public record IdentityView(String kind, boolean confirmed, String fingerprint, String pendingFingerprint,
            Instant confirmedAt) {
    }

    public record TestSummary(Instant at, boolean ok, String json) {
    }

    /** Resposta da API: nunca inclui senha, chave ou texto cifrado. */
    public record MachineView(
            long id,
            String name,
            String description,
            String host,
            Protocol protocol,
            int port,
            String ftpsMode,
            String username,
            String authType,
            boolean hasPassword,
            int maxConnections,
            Capabilities capabilities,
            Fidelity fidelity,
            List<String> warnings,
            IdentityView identity,
            Instant lastTestAt,
            String lastTest) {
    }

    public record ConfirmIdentityRequest(@NotBlank String fingerprint) {
    }

    public record BrowseResult(String path, String parent, List<BrowseItem> items) {
    }

    public record BrowseItem(String name, String path, String type, long size, Instant mtime, Integer mode,
            String linkTarget) {
    }
}
