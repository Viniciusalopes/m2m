package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.files.Capabilities;

/** Matriz de fidelidade exibida ao usuário: o que o protocolo preserva nesta máquina. */
public record Fidelity(boolean content, boolean mtime, boolean permissions, boolean symlinks) {

    public static Fidelity of(Protocol protocol, Capabilities caps) {
        return switch (protocol) {
            case LOCAL, SFTP -> new Fidelity(true, true, true, true);
            case FTP, FTPS -> new Fidelity(true, caps.setsMtime(), false, false);
        };
    }

    public boolean full() {
        return content && mtime && permissions && symlinks;
    }
}
