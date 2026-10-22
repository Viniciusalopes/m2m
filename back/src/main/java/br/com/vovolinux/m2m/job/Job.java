package br.com.vovolinux.m2m.job;

import java.time.Instant;

/** Linha de {@code job}; {@code progress}, {@code params} e {@code result} são JSON em texto. */
public record Job(
        long id,
        long ownerId,
        String type,
        String status,
        String step,
        String progress,
        String trigger,
        Long profileId,
        String profileName,
        String params,
        String result,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
