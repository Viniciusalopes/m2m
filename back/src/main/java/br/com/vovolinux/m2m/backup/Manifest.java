package br.com.vovolinux.m2m.backup;

import java.util.List;

/**
 * Manifesto de um backup (D4): quem, de onde, com que fidelidade, o mapeamento de caminhos vigente e a lista de
 * itens. A lista de itens é gravada em streaming (ver {@link ManifestIO}); aqui ficam os campos do cabeçalho.
 */
public final class Manifest {

    public static final int FORMAT_VERSION = 1;
    public static final String INTERNAL_ENTRY = ".m2m-manifest.json";

    private Manifest() {
    }

    public record MappingRef(Long machineId, String machine, String path) {
    }

    public record Directory(int index, String path, List<MappingRef> mappings) {
    }

    public record Header(
            int formatVersion,
            String type,
            String profile,
            String sourceMachine,
            String sourceProtocol,
            boolean fullFidelity,
            List<String> fidelityWarnings,
            String createdAt,
            List<Directory> directories,
            /** PRE_SYNC: itens do destino que a sincronização criou (D20). */
            List<String> createdBySync) {
    }

    /**
     * Um item do backup.
     *
     * @param dir  índice do diretório de origem (dir-N)
     * @param path caminho relativo ao diretório ("" é o próprio diretório)
     * @param type FILE, DIR ou SYMLINK
     * @param mtime milissegundos desde a época
     * @param mode permissões Unix, ou {@code null} quando o protocolo não informa
     * @param link alvo do link simbólico
     * @param sha256 hash do conteúdo (só arquivos)
     */
    public record Item(int dir, String path, String type, long size, long mtime, Integer mode, String link,
            String sha256) {

        /** Nome da entrada dentro do tar. */
        public String entryName() {
            String base = "dir-" + dir;
            return path.isEmpty() ? base : base + "/" + path;
        }
    }
}
