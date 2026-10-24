package br.com.vovolinux.m2m.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** UID e GID de quem roda os testes, lidos de um diretório que o próprio teste criou. */
final class HostUser {

    private HostUser() {
    }

    static String uid(Path criadoPeloTeste) throws IOException {
        return String.valueOf(Files.getAttribute(criadoPeloTeste, "unix:uid"));
    }

    static String gid(Path criadoPeloTeste) throws IOException {
        return String.valueOf(Files.getAttribute(criadoPeloTeste, "unix:gid"));
    }
}
