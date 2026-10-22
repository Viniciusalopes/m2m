package br.com.vovolinux.m2m.audit;

import java.time.Instant;

public record AuditEvent(
        long id,
        Instant occurredAt,
        String username,
        String ip,
        String action,
        String target,
        String result,
        String details) {
}
