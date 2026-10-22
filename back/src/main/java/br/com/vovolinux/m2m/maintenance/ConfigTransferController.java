package br.com.vovolinux.m2m.maintenance;

import br.com.vovolinux.m2m.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exportar e importar a configuração, cifrada por senha. */
@RestController
@RequestMapping("/api/config")
public class ConfigTransferController {

    public record ExportRequest(String password) {
    }

    public record ImportRequest(String password, String content) {
    }

    private final ConfigTransferService service;
    private final CurrentUser currentUser;

    public ConfigTransferController(ConfigTransferService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestBody ExportRequest req) {
        String file = service.export(currentUser.require().id(), req.password());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("m2m-configuracao.m2mcfg").build().toString())
                .body(file);
    }

    @PostMapping("/import/preview")
    public ConfigTransferService.Preview preview(@RequestBody ImportRequest req) {
        return service.preview(currentUser.require().id(), req.content(), req.password());
    }

    @PostMapping("/import")
    public ConfigTransferService.Preview apply(@RequestBody ImportRequest req) {
        return service.apply(currentUser.require().id(), req.content(), req.password());
    }
}
