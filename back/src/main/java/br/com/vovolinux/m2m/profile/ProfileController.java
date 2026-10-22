package br.com.vovolinux.m2m.profile;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobController;
import br.com.vovolinux.m2m.profile.ProfileDtos.BackupRequest;
import br.com.vovolinux.m2m.profile.ProfileDtos.Defaults;
import br.com.vovolinux.m2m.profile.ProfileDtos.ProfileRequest;
import br.com.vovolinux.m2m.profile.ProfileDtos.ProfileView;
import br.com.vovolinux.m2m.security.CurrentUser;
import jakarta.validation.Valid;
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
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService service;
    private final CurrentUser currentUser;
    private final JobController jobs;

    public ProfileController(ProfileService service, CurrentUser currentUser, JobController jobs) {
        this.service = service;
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    private long owner() {
        return currentUser.require().id();
    }

    @GetMapping
    public List<ProfileView> list() {
        return service.list(owner());
    }

    @GetMapping("/defaults")
    public Defaults defaults() {
        return service.defaults(owner());
    }

    @GetMapping("/{id}")
    public ProfileView get(@PathVariable long id) {
        return service.get(owner(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView create(@Valid @RequestBody ProfileRequest req) {
        return service.create(owner(), req);
    }

    @PutMapping("/{id}")
    public ProfileView update(@PathVariable long id, @Valid @RequestBody ProfileRequest req) {
        return service.update(owner(), id, req);
    }

    /** {@code keepBackups=true} deixa os backups como órfãos; {@code false} exclui os arquivos junto. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestParam(defaultValue = "true") boolean keepBackups) {
        service.delete(owner(), id, keepBackups);
    }

    @PostMapping("/{id}/backup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobController.JobView startBackup(@PathVariable long id, @RequestBody(required = false) BackupRequest req) {
        Job job = service.startBackup(owner(), id, req == null ? null : req.complement());
        return jobs.view(job);
    }
}
