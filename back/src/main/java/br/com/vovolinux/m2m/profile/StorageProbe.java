package br.com.vovolinux.m2m.profile;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.Machine;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Verificações de caminho numa máquina, feitas ao salvar um perfil. */
@Component
public class StorageProbe {

    private final GatewayPool pool;

    public StorageProbe(GatewayPool pool) {
        this.pool = pool;
    }

    /** O diretório existe (e é diretório) na máquina? */
    public boolean directoryExists(Machine machine, String dir) throws IOException {
        try (GatewayPool.Lease lease = pool.acquire(machine)) {
            try {
                return lease.gateway().lstat(dir).isDirectory();
            } catch (java.nio.file.NoSuchFileException e) {
                return false;
            } catch (IOException e) {
                lease.discard();
                throw e;
            }
        }
    }

    /**
     * Cria o diretório se faltar e grava/remove um único arquivo exclusivo.
     *
     * @return {@code true} se a gravação é permitida
     */
    public boolean canWrite(Machine machine, String dir) throws IOException {
        try (GatewayPool.Lease lease = pool.acquire(machine)) {
            FileGateway gw = lease.gateway();
            String arquivo = dir + "/.m2m-write-test-" + UUID.randomUUID();
            try {
                gw.mkdirs(dir);
                try (OutputStream out = gw.openWrite(arquivo, 0)) {
                    out.write('m');
                }
                gw.delete(arquivo);
                return true;
            } catch (AccessDeniedException e) {
                return false;
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("permission")) {
                    return false;
                }
                lease.discard();
                throw e;
            }
        }
    }
}
