package br.com.vovolinux.m2m.dashboard;

import br.com.vovolinux.m2m.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public DashboardService.Dashboard get() {
        return service.build(currentUser.require().id());
    }
}
