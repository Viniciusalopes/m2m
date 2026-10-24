package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.vovolinux.m2m.config.AppProperties;
import br.com.vovolinux.m2m.user.AdminBootstrap;
import br.com.vovolinux.m2m.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminBootstrap com(String user, String senha, String email) {
        return new AdminBootstrap(users, encoder,
                new AppProperties(new AppProperties.Admin(user, senha, email), "x".repeat(20)));
    }

    @Test
    void bancoVazioSemSenhaImpedeAInicializacao() {
        when(users.count()).thenReturn(0L);
        assertThatThrownBy(() -> com("admin", "", "a@b.c").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_PASSWORD");
        verify(users, never()).create(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bancoVazioCriaAdminComSenhaEmHash() {
        when(users.count()).thenReturn(0L);
        com("ana", "Senha-Forte-123", "ana@exemplo.test").run(null);
        verify(users).create(eq("ana"), eq("ana@exemplo.test"), any(), eq("ADMIN"));
        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).create(anyString(), anyString(), hash.capture(), anyString());
        assertThat(hash.getValue()).isNotEqualTo("Senha-Forte-123");
        assertThat(encoder.matches("Senha-Forte-123", hash.getValue())).isTrue();
    }

    @Test
    void bancoComUsuariosNaoRecriaNada() {
        when(users.count()).thenReturn(1L);
        com("admin", "", "").run(null);
        verify(users, never()).create(anyString(), anyString(), anyString(), anyString());
    }
}
