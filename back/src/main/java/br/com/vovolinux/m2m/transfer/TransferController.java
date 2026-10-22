package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.job.SseHub;
import br.com.vovolinux.m2m.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API da tela de transferência: listagem de painéis, fila, conflitos, operações de arquivo, filtros e comparação. */
@RestController
@RequestMapping("/api")
public class TransferController {

    /** O item como a tela o vê. */
    public record View(long id, String batchId, String op, String kind, String status, String srcMachine,
            String srcPath, String dstMachine, String dstPath, long size, long bytesDone, double percent,
            String skipReason, String note, String errorCode, JsonNode errorParams, boolean resumable,
            Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    public record PathRequest(Long machineId, String path) {
    }

    public record RenameRequest(Long machineId, String from, String to) {
    }

    public record DeleteRequest(Long machineId, String path, Boolean confirmed) {
    }

    public record ResumeAllRequest(List<String> statuses) {
    }

    public record FilterSetRequest(String name, TransferFilter.Definition definition) {
    }

    private final TransferService service;
    private final CurrentUser currentUser;
    private final SseHub hub;
    private final JsonMapper json;

    public TransferController(TransferService service, CurrentUser currentUser, SseHub hub, JsonMapper json) {
        this.service = service;
        this.currentUser = currentUser;
        this.hub = hub;
        this.json = json;
    }

    private long owner() {
        return currentUser.require().id();
    }

    View view(TransferItem i) {
        double pct = i.size() <= 0 ? (TransferItem.COMPLETED.equals(i.status()) ? 100 : 0)
                : Math.min(100.0, i.bytesDone() * 100.0 / i.size());
        return new View(i.id(), i.batchId().toString(), i.op(), i.kind(), i.status(), i.srcMachineName(), i.srcPath(),
                i.dstMachineName(), i.dstPath(), i.size(), i.bytesDone(), pct, i.skipReason(), i.note(), i.errorCode(),
                i.errorParams() == null ? null : json.readTree(i.errorParams()), i.restartable(), i.createdAt(),
                i.startedAt(), i.finishedAt());
    }

    @PostMapping("/transfers/list")
    public TransferService.Listing list(@RequestBody TransferService.ListRequest req) {
        return service.list(owner(), req);
    }

    @PostMapping("/transfers/compare")
    public TransferService.CompareResult compare(@RequestBody TransferService.CompareRequest req) {
        return service.compare(owner(), req);
    }

    @PostMapping("/transfers/plan")
    public TransferService.PlanResult plan(@RequestBody TransferService.PlanRequest req) {
        return service.plan(owner(), req);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TransferService.PlanResult start(@RequestBody TransferService.PlanRequest req) {
        return service.start(owner(), req);
    }

    @GetMapping("/transfers")
    public List<View> queue(@RequestParam(required = false) String status, @RequestParam(required = false) UUID batch,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size) {
        return service.queue(owner(), status, batch, page, size).stream().map(this::view).toList();
    }

    @GetMapping("/transfers/operations")
    public List<View> operations(@RequestParam(defaultValue = "100") int limit) {
        return service.operations(owner(), limit).stream().map(this::view).toList();
    }

    @PostMapping("/transfers/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id) {
        service.cancel(owner(), id);
    }

    @PostMapping("/transfers/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable long id) {
        service.resume(owner(), id);
    }

    @PostMapping("/transfers/cancel-all")
    public Map<String, Integer> cancelAll() {
        return Map.of("canceled", service.cancelAll(owner()));
    }

    @PostMapping("/transfers/resume-all")
    public Map<String, Integer> resumeAll(@RequestBody(required = false) ResumeAllRequest req) {
        List<String> statuses = req == null || req.statuses() == null ? List.of("FAILED") : req.statuses();
        return Map.of("requeued", service.resumeAll(owner(), statuses));
    }

    @PostMapping("/transfers/fs/mkdir")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mkdir(@RequestBody PathRequest req) {
        service.mkdir(owner(), req.machineId(), req.path());
    }

    @PostMapping("/transfers/fs/rename")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@RequestBody RenameRequest req) {
        service.rename(owner(), req.machineId(), req.from(), req.to());
    }

    @PostMapping("/transfers/fs/count")
    public Map<String, Long> count(@RequestBody PathRequest req) {
        return Map.of("items", service.countItems(owner(), req.machineId(), req.path()));
    }

    @PostMapping("/transfers/fs/delete")
    public Map<String, Long> delete(@RequestBody DeleteRequest req) {
        return Map.of("deleted", service.delete(owner(), req.machineId(), req.path(),
                Boolean.TRUE.equals(req.confirmed())));
    }

    /** Eventos de progresso de todas as transferências do usuário. */
    @GetMapping(value = "/transfers/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return hub.subscribe("transfer." + owner());
    }

    @GetMapping("/transfer-filters")
    public List<Map<String, Object>> filterSets() {
        return service.filterSets(owner()).stream()
                .map(f -> Map.<String, Object>of("id", f.id(), "name", f.name(), "definition",
                        json.readTree(f.definition())))
                .toList();
    }

    @PostMapping("/transfer-filters")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> saveFilterSet(@RequestBody FilterSetRequest req) {
        return Map.of("id", service.saveFilterSet(owner(), req.name(), req.definition()));
    }

    @DeleteMapping("/transfer-filters/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFilterSet(@PathVariable long id) {
        service.deleteFilterSet(owner(), id);
    }
}
