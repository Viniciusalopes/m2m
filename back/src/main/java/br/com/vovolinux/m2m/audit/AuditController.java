package br.com.vovolinux.m2m.audit;

import br.com.vovolinux.m2m.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int EXPORT_LIMIT = 100_000;

    private final AuditRepository repository;
    private final CurrentUser currentUser;

    public AuditController(AuditRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public record Page(List<AuditEvent> items, long total) {
    }

    @GetMapping
    public Page list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        long owner = currentUser.require().id();
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;
        return new Page(repository.search(owner, from, to, user, action, limit, offset),
                repository.count(owner, from, to, user, action));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String action,
            HttpServletResponse response) throws IOException {
        long owner = currentUser.require().id();
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"auditoria.csv\"");
        PrintWriter out = response.getWriter();
        out.print("﻿");
        out.println("data_hora;usuario;ip;acao;alvo;resultado;detalhes");
        for (AuditEvent e : repository.search(owner, from, to, user, action, EXPORT_LIMIT, 0)) {
            out.println(String.join(";", csv(e.occurredAt().toString()), csv(e.username()), csv(e.ip()),
                    csv(e.action()), csv(e.target()), csv(e.result()), csv(e.details())));
        }
        out.flush();
    }

    /** Escapa aspas e neutraliza fórmulas de planilha (=, +, -, @). */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
