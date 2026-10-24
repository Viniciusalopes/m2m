package br.com.vovolinux.m2m.backup;

import br.com.vovolinux.m2m.crypto.ArchiveCrypto;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayException;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import br.com.vovolinux.m2m.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Operações sobre os arquivos de um backup no armazenamento (arquivo e manifesto lateral). */
@Component
public class ArchiveFiles {

    public static final String MANIFEST_SUFFIX = ".manifest";

    private final GatewayPool pool;
    private final MachineRepository machines;
    private final ArchiveCrypto crypto;

    public ArchiveFiles(GatewayPool pool, MachineRepository machines, ArchiveCrypto crypto) {
        this.pool = pool;
        this.machines = machines;
        this.crypto = crypto;
    }

    /** O {@code .tar.zst} do backup em claro (decifrado quando for {@code .enc}). */
    public InputStream openArchive(FileGateway gw, BackupArchive a) throws IOException {
        return decrypted(gw.openRead(path(a), 0), a);
    }

    /** O manifesto lateral (ainda comprimido com zstd), decifrado quando o backup é criptografado. */
    public InputStream openSideManifest(FileGateway gw, BackupArchive a) throws IOException {
        return decrypted(gw.openRead(path(a) + MANIFEST_SUFFIX, 0), a);
    }

    private InputStream decrypted(InputStream raw, BackupArchive a) throws IOException {
        if (!a.encrypted()) {
            return raw;
        }
        try {
            return crypto.decrypt(crypto.byId(a.keyId()), raw);
        } catch (ArchiveCrypto.KeyUnavailable e) {
            raw.close();
            throw new GatewayException("backup.chave-ausente", e.getMessage(), Map.of("arquivo", a.fileName()), e);
        } catch (IOException e) {
            raw.close();
            throw e;
        }
    }

    public static String path(BackupArchive a) {
        return a.storageDir() + "/" + a.fileName();
    }

    /**
     * Recusa usar um backup cuja máquina de armazenamento foi removida: o arquivo continua no disco dela e volta a ficar
     * disponível quando uma máquina com o mesmo nome é cadastrada de novo.
     */
    public static void requireStorage(BackupArchive a) {
        if (a.storageMachineId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "backup.armazenamento-indisponivel",
                    Map.of("maquina", String.valueOf(a.storageMachineName())));
        }
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
