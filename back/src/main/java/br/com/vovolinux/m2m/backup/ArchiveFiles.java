package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Operações sobre os arquivos de um backup no armazenamento (arquivo e manifesto lateral). */
@Component
public class ArchiveFiles {

    public static final String MANIFEST_SUFFIX = ".manifest";

    private final GatewayPool pool;
    private final MachineRepository machines;

    public ArchiveFiles(GatewayPool pool, MachineRepository machines) {
        this.pool = pool;
        this.machines = machines;
    }

    public static String path(BackupArchive a) {
        return a.storageDir() + "/" + a.fileName();
    }

    public Optional<Machine> storageOf(BackupArchive a) {
        if (a.storageMachineId() == null) {
            return Optional.empty();
        }
        return machines.find(a.ownerId(), a.storageMachineId());
    }

    /** Remove o arquivo e o manifesto lateral; ausência não é erro. */
    public void delete(BackupArchive a) throws IOException {
        Machine m = storageOf(a).orElseThrow(() -> new IOException("Máquina de armazenamento removida"));
        try (GatewayPool.Lease lease = pool.acquire(m)) {
            FileGateway gw = lease.gateway();
            for (String p : new String[] {path(a), path(a) + MANIFEST_SUFFIX}) {
                try {
                    gw.delete(p);
                } catch (NoSuchFileException ignorado) {
                    // já não existe
                } catch (IOException e) {
                    lease.discard();
                    throw e;
                }
            }
        }
    }
}
