package br.com.vovolinux.m2m.user;

import java.time.Instant;

public record AppUser(
        long id,
        String username,
        String email,
        String passwordHash,
        String role,
        boolean enabled,
        int failedAttempts,
        Instant lockedUntil) {
}
