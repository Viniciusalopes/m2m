package br.com.vovolinux.m2m.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Usuário autenticado; carrega o id para o filtro por dono (owner_id) nos repositórios. */
public class M2mUserDetails implements UserDetails {

    private final long id;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;
    private final boolean locked;
    private final Instant lockedUntil;

    public M2mUserDetails(long id, String username, String email, String passwordHash, String role, boolean enabled,
            Instant lockedUntil) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.lockedUntil = lockedUntil;
        this.locked = lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public long id() {
        return id;
    }

    public String email() {
        return email;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
