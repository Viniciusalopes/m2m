package br.com.vovolinux.m2m.files;

/**
 * O que uma conexão preserva e permite; usado pelo motor e pela interface para os avisos de fidelidade (D2).
 *
 * @param preservesMode  permissões Unix
 * @param preservesLinks links simbólicos
 * @param setsMtime      data de modificação
 * @param remoteHash     SHA-256 calculado no servidor (canal exec)
 * @param freeSpace      consulta de espaço livre
 * @param writable       o usuário pode gravar (conhecido após o teste de conexão; {@code true} por padrão)
 */
public record Capabilities(
        boolean preservesMode,
        boolean preservesLinks,
        boolean setsMtime,
        boolean remoteHash,
        boolean freeSpace,
        boolean writable) {

    public static Capabilities full() {
        return new Capabilities(true, true, true, false, true, true);
    }

    public Capabilities withRemoteHash(boolean v) {
        return new Capabilities(preservesMode, preservesLinks, setsMtime, v, freeSpace, writable);
    }

    public Capabilities withFreeSpace(boolean v) {
        return new Capabilities(preservesMode, preservesLinks, setsMtime, remoteHash, v, writable);
    }

    public Capabilities withWritable(boolean v) {
        return new Capabilities(preservesMode, preservesLinks, setsMtime, remoteHash, freeSpace, v);
    }

    public boolean fullFidelity() {
        return preservesMode && preservesLinks && setsMtime;
    }
}
