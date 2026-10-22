package br.com.vovolinux.m2m.security;

import br.com.vovolinux.m2m.user.AppUser;
import br.com.vovolinux.m2m.user.UserRepository;
import java.time.Instant;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class M2mUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public M2mUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException(username));
        // Bloqueio vencido: recomeça a contagem de falhas.
        if (u.lockedUntil() != null && !u.lockedUntil().isAfter(Instant.now())) {
            users.resetAttempts(u.id());
            u = users.findById(u.id()).orElseThrow();
        }
        return new M2mUserDetails(u.id(), u.username(), u.email(), u.passwordHash(), u.role(), u.enabled(),
                u.lockedUntil());
    }
}
