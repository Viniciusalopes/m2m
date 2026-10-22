package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.job.JobController;
import br.com.vovolinux.m2m.security.CurrentUser;
import br.com.vovolinux.m2m.sync.SyncService.Preview;
import br.com.vovolinux.m2m.sync.SyncService.ScheduleRequest;
import br.com.vovolinux.m2m.sync.SyncService.ScheduleView;
import br.com.vovolinux.m2m.sync.SyncService.SyncRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SyncController {

    private final SyncService service;
    private final CurrentUser currentUser;
    private final JobController jobs;

    public SyncController(SyncService service, CurrentUser currentUser, JobController jobs) {
        this.service = service;
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @PostMapping("/profiles/{id}/sync/preview")
    public Preview preview(@PathVariable long id, @RequestBody SyncRequest req) {
        return service.preview(owner(), id, req);
    }

    @PostMapping("/profiles/{id}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView start(@PathVariable long id, @RequestBody SyncRequest req) {
        return jobs.view(service.start(owner(), id, req));
    }

    @GetMapping("/profiles/{id}/sync-schedules")
    public List<ScheduleView> schedules(@PathVariable long id) {
        return service.schedules(owner(), id);
    }

    @PostMapping("/profiles/{id}/sync-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleView create(@PathVariable long id, @RequestBody ScheduleRequest req) {
        return service.saveSchedule(owner(), id, null, req);
    }

    @PutMapping("/profiles/{id}/sync-schedules/{scheduleId}")
    public ScheduleView update(@PathVariable long id, @PathVariable long scheduleId, @RequestBody ScheduleRequest req) {
        return service.saveSchedule(owner(), id, scheduleId, req);
    }

    @DeleteMapping("/profiles/{id}/sync-schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @PathVariable long scheduleId) {
        service.deleteSchedule(owner(), id, scheduleId);
    }

    /** Confirma uma sincronização agendada que parou no limite de exclusões. */
    @PostMapping("/jobs/{id}/confirm-sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView confirm(@PathVariable long id, @RequestBody(required = false) SyncRequest flags) {
        return jobs.view(service.confirmAwaiting(owner(), id, flags));
    }

    @PostMapping("/backups/{id}/undo-sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView undo(@PathVariable long id) {
        return jobs.view(service.undo(owner(), id));
    }

    @GetMapping("/syncs")
    public List<SyncRunRepository.Row> history(@RequestParam(required = false) Long profileId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.history(owner(), profileId, page, size);
    }
}
