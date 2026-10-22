package br.com.vovolinux.m2m.files;

public enum FileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    /** FIFO, socket, dispositivo ou tipo não reconhecido: nunca é aberto (D4). */
    SPECIAL
}
