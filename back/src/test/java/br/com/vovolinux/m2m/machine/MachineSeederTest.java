package br.com.vovolinux.m2m.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.user.AppUser;
import br.com.vovolinux.m2m.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MachineSeederTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MachineRepository machines = mock(MachineRepository.class);

    @TempDir
    Path hostfs;

    @BeforeEach
    void bancoComAdminESemMaquinas() throws Exception {
        Files.createDirectories(hostfs.resolve("home"));
        when(users.firstUsername()).thenReturn(Optional.of("admin"));
        when(users.findByUsername("admin"))
                .thenReturn(Optional.of(new AppUser(7, "admin", "", "", "ADMIN", true, 0, null)));
        when(machines.count(7)).thenReturn(0L);
        when(machines.localExists()).thenReturn(false);
    }

    private MachineSeeder com(boolean enabled, String name) {
        return new MachineSeeder(new LocalProperties(enabled, hostfs.toString(), List.of("/home"), name), users,
                machines);
    }

    private List<Machine> inseridas(int quantidade) {
        ArgumentCaptor<Machine> captor = ArgumentCaptor.forClass(Machine.class);
        verify(machines, times(quantidade)).insert(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void semNomeCriaSoAMaquinaLocalComONomePadrao() {
        for (String vazio : new String[] {"", "  ", null}) {
            clearInvocations(machines);
            com(true, vazio).run(null);
            List<Machine> criadas = inseridas(1);
            assertThat(criadas.getFirst().name()).isEqualTo("LOCAL");
            assertThat(criadas.getFirst().protocol()).isEqualTo(Protocol.LOCAL);
        }
    }

    @Test
    void comUmaLocalJaCadastradaNaoCriaOutra() {
        when(machines.localExists()).thenReturn(true);
        com(true, "NOTEBOOK-NOVO").run(null);
        verify(machines, never()).insert(any());
    }

    @Test
    void comNomeCriaSoAMaquinaLocal() {
        com(true, " NOTEBOOK-NOVO ").run(null);
        List<Machine> criadas = inseridas(1);
        assertThat(criadas.getFirst().name()).isEqualTo("NOTEBOOK-NOVO");
        assertThat(criadas.getFirst().protocol()).isEqualTo(Protocol.LOCAL);
        assertThat(criadas.getFirst().ownerId()).isEqualTo(7);
    }

    @Test
    void comNomeEMaquinasCadastradasNaoCriaNada() {
        when(machines.count(7)).thenReturn(3L);
        com(true, "NOTEBOOK-NOVO").run(null);
        verify(machines, never()).insert(any());
    }

    @Test
    void localDesligadoNaoCriaNadaMesmoComNome() {
        com(false, "NOTEBOOK-NOVO").run(null);
        verify(machines, never()).insert(any());
        verify(machines, never()).count(anyLong());
    }
}
