package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.GatewayPool;
import br.com.vovolinux.m2m.job.OutboxPublisher;
import br.com.vovolinux.m2m.machine.Machine;
import br.com.vovolinux.m2m.machine.MachineRepository;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reinício da aplicação: o que estava transferindo vira INTERRUPTED (o temporário parcial fica para a retomada) e os
 * itens ainda aguardando continuam na fila (republicados se a mensagem se perdeu). Também limpa temporários velhos.
 */
@Component
@Order(21)
public class TransferRecovery implements ApplicationRunner {

    public static final int TEMP_DAYS = 7;
    private static final Logger log = LoggerFactory.getLogger(TransferRecovery.class);

    private final TransferRepository repository;
    private final OutboxPublisher outbox;
    private final MachineRepository machines;
    private final GatewayPool pool;
    private final boolean naPartida;

    public TransferRecovery(TransferRepository repository, OutboxPublisher outbox, MachineRepository machines,
            GatewayPool pool, @Value("${m2m.recuperacao.na-partida:true}") boolean naPartida) {
        this.repository = repository;
        this.outbox = outbox;
        this.machines = machines;
        this.pool = pool;
        this.naPartida = naPartida;
    }

    /**
     * Na partida da aplicação. Os testes desligam (m2m.recuperacao.na-partida=false): vários contextos Spring dividem
     * o mesmo banco e a mesma fila, e um contexto novo interromperia as transferências que outro está executando.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (naPartida) {
            recuperar();
        }
    }

    public void recuperar() {
        for (TransferItem i : repository.markTransferringInterrupted()) {
            log.warn("Transferência {} estava em andamento quando a aplicação parou: marcada como interrompida",
                    i.id());
        }
        List<TransferItem> semPublicacao = repository.queuedWithoutPendingPublication();
        semPublicacao.forEach(i -> repository.insertOutbox(i.id()));
        if (!semPublicacao.isEmpty()) {
            log.info("{} transferência(s) na fila republicada(s)", semPublicacao.size());
        }
        outbox.kick();
    }

    /** Temporários de itens não retomados em 7 dias são removidos do destino. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Sao_Paulo")
    public void cleanStaleTemporaries() {
        for (TransferItem i : repository.staleTemporaries(TEMP_DAYS)) {
            Machine m = i.dstMachineId() == null ? null : machines.find(i.ownerId(), i.dstMachineId()).orElse(null);
            if (m == null) {
                repository.clearTmp(i.id());
                continue;
            }
            try (GatewayPool.Lease lease = pool.acquire(m)) {
                FileGateway gw = lease.gateway();
                try {
                    gw.delete(i.tmpPath());
                } catch (NoSuchFileException ignorado) {
                    // já foi removido
                }
                repository.clearTmp(i.id());
            } catch (IOException | RuntimeException e) {
                log.warn("Não foi possível limpar o temporário {}: {}", i.tmpPath(), e.getMessage());
            }
        }
    }
}
