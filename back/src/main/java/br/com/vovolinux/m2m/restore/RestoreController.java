package br.com.vovolinux.m2m.restore;

import br.com.vovolinux.m2m.job.JobController;
import br.com.vovolinux.m2m.restore.RestoreService.Conflict;
import br.com.vovolinux.m2m.restore.RestoreService.DefaultTarget;
import br.com.vovolinux.m2m.restore.RestoreService.Preview;
import br.com.vovolinux.m2m.restore.RestoreService.RestoreRequest;
import br.com.vovolinux.m2m.restore.RestoreService.SelectiveRequest;
import br.com.vovolinux.m2m.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RestoreController {

    private final RestoreService service;
    private final RestoreRunRepository runs;
    private final CurrentUser currentUser;
    private final JobController jobs;

    public RestoreController(RestoreService service, RestoreRunRepository runs, CurrentUser currentUser,
            JobController jobs) {
        this.service = service;
        this.runs = runs;
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @GetMapping("/backups/{id}/restore/defaults")
    public List<DefaultTarget> defaults(@PathVariable long id, @RequestParam long machineId) {
        return service.defaults(owner(), id, machineId);
    }

    @PostMapping("/backups/{id}/restore/preview")
    public Preview preview(@PathVariable long id, @RequestBody RestoreRequest req) {
        return service.preview(owner(), id, req);
    }

    @PostMapping("/backups/{id}/restore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView restore(@PathVariable long id, @RequestBody RestoreRequest req) {
        return jobs.view(service.start(owner(), id, req, false));
    }

    @PostMapping("/backups/{id}/undo")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView undo(@PathVariable long id) {
        return jobs.view(service.undo(owner(), id));
    }

    @PostMapping("/backups/{id}/selective/plan")
    public List<Conflict> plan(@PathVariable long id, @RequestBody SelectiveRequest req) {
        return service.plan(owner(), id, req);
    }

    @PostMapping("/backups/{id}/selective")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView selective(@PathVariable long id, @RequestBody SelectiveRequest req) {
        return jobs.view(service.startSelective(owner(), id, req));
    }

    @GetMapping("/restores")
    public List<RestoreRunRepository.Row> history(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return runs.list(owner(), limit, Math.max(page, 0) * limit);
    }
}
