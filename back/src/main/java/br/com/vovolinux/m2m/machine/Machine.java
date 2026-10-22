package br.com.vovolinux.m2m.machine;

import java.time.Instant;

/** Linha da tabela {@code machine}; contém a credencial cifrada e por isso nunca sai pela API. */
public record Machine(
        long id,
        long ownerId,
        String name,
        String description,
        String host,
        Protocol protocol,
        int port,
        String ftpsMode,
        String username,
        String authType,
        byte[] passwordEnc,
        int maxConnections,
        String capabilitiesJson,
        Instant lastTestAt,
        String lastTestJson) {
}
