package br.com.vovolinux.m2m.files;

import java.time.Instant;

/**
 * Metadados de um item, obtidos sem seguir links simbólicos.
 *
 * @param path       caminho completo (POSIX, no espaço de nomes da máquina)
 * @param mode       permissões Unix (ex.: 0755), ou {@code null} quando o protocolo não informa
 * @param linkTarget alvo do link simbólico, somente para {@link FileType#SYMLINK}
 */
public record FileEntry(
        String name,
        String path,
        FileType type,
        long size,
        Instant mtime,
        Integer mode,
        String linkTarget) {

    public boolean isDirectory() {
        return type == FileType.DIRECTORY;
    }
}
