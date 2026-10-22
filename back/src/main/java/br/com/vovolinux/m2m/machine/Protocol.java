package br.com.vovolinux.m2m.machine;

public enum Protocol {
    LOCAL(0),
    SFTP(22),
    FTP(21),
    FTPS(21);

    private final int defaultPort;

    Protocol(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    /** Porta padrão; FTPS implícito usa 990. */
    public int defaultPort(String ftpsMode) {
        return this == FTPS && "IMPLICIT".equals(ftpsMode) ? 990 : defaultPort;
    }
}
