package br.com.vovolinux.m2m.files;

/** Espaço de um sistema de arquivos: livre e total, em bytes. */
public record SpaceInfo(long freeBytes, long totalBytes) {

    public double freePercent() {
        return totalBytes <= 0 ? 100.0 : freeBytes * 100.0 / totalBytes;
    }
}
