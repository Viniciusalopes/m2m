package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.config.LocalProperties;
import br.com.vovolinux.m2m.user.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Máquina inicial: com o acesso LOCAL ativo e nenhuma máquina cadastrada, cria só a máquina LOCAL com o nome de
 * M2M_LOCAL_NAME (padrão {@code LOCAL}). Sem LOCAL (ex.: VPS) nada é criado. Também valida o bind mount do host.
 */
@Component
@Order(10)
public class MachineSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MachineSeeder.class);

    /** Nome da máquina LOCAL quando M2M_LOCAL_NAME não é informado. */
    static final String NOME_PADRAO = "LOCAL";

    private final LocalProperties local;
    private final UserRepository users;
    private final MachineRepository machines;

    public MachineSeeder(LocalProperties local, UserRepository users, MachineRepository machines) {
        this.local = local;
        this.users = users;
        this.machines = machines;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!local.enabled()) {
            return;
        }
        Path base = Path.of(local.baseDir());
        for (String root : local.roots()) {
            if (!Files.isDirectory(base.resolve(root.substring(1)))) {
                throw new IllegalStateException("M2M_LOCAL_ENABLED=true mas " + base.resolve(root.substring(1))
                        + " não existe: monte o diretório do host (HOST_HOME_BIND) ou desative o acesso LOCAL");
            }
        }
        users.findByUsername(firstUsername()).ifPresent(u -> {
            if (machines.count(u.id()) > 0) {
                return;
            }
            if (machines.localExists()) {
                return;
            }
            String name = local.name() == null || local.name().isBlank() ? NOME_PADRAO : local.name().strip();
            machines.insert(new Machine(0, u.id(), name, "Host onde o m2m roda", "localhost", Protocol.LOCAL, 0, null,
                    null, null, null, 4, "{}", null, null));
            log.info("Máquina inicial criada: {} (LOCAL)", name);
        });
    }

    private String firstUsername() {
        return users.firstUsername().orElse("");
    }
}
