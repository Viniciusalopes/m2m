package br.com.vovolinux.m2m.files.sftp;

import br.com.vovolinux.m2m.files.GatewayException;

/** Decide se a chave apresentada pelo servidor é a identidade confirmada da máquina. */
@FunctionalInterface
public interface HostKeyPolicy {

    /**
     * @param fingerprint impressão digital no formato do OpenSSH (ex.: {@code SHA256:abc...})
     * @throws GatewayException {@code identidade.confirmacao-necessaria} ou {@code identidade.alterada}
     */
    void verify(String fingerprint) throws GatewayException;
}
