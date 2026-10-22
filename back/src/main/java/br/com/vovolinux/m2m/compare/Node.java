package br.com.vovolinux.m2m.compare;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileType;

/**
 * Item de uma árvore comparável (diretório vivo numa máquina ou itens de um manifesto de backup).
 *
 * @param mode        permissões, ou {@code null} se o lado não as informa
 * @param linkTarget  alvo do link simbólico
 * @param knownSha256 hash já conhecido (manifesto); {@code null} se precisa ser calculado
 */
public record Node(String name, FileType type, long size, long mtimeMillis, Integer mode, String linkTarget,
        String knownSha256) {

    public static Node of(FileEntry e) {
        return new Node(e.name(), e.type(), e.size(), e.mtime().toEpochMilli(), e.mode(), e.linkTarget(), null);
    }

    public boolean isDir() {
        return type == FileType.DIRECTORY;
    }
}
