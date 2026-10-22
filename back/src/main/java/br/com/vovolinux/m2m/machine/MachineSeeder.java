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
 * Valores iniciais do NOTEBOOK: com o acesso LOCAL ativo e nenhuma máquina cadastrada, cria NOTEBOOK (LOCAL) e
 * NOTEBOOK-REMOTO (SFTP, 192.168.0.10:22). Sem LOCAL (ex.: VPS) nada é criado. Também valida o bind mount do host.
 */
@Component
@Order(10)
public class MachineSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MachineSeeder.class);

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
            if (!machines.localExists()) {
                machines.insert(new Machine(0, u.id(), "NOTEBOOK", "Host onde o m2m roda", "localhost", Protocol.LOCAL, 0,
                        null, null, null, null, 4, "{}", null, null));
            }
            machines.insert(new Machine(0, u.id(), "NOTEBOOK-REMOTO", "Notebook de trabalho", "192.168.0.10", Protocol.SFTP,
                    22, null, "usuario", "KEY", null, 4, "{}", null, null));
            log.info("Máquinas iniciais criadas: NOTEBOOK (LOCAL) e NOTEBOOK-REMOTO (SFTP 192.168.0.10:22)");
        });
    }

    private String firstUsername() {
        return users.firstUsername().orElse("");
    }
}
