package br.com.vovolinux.m2m.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Força a emissão do cookie XSRF-TOKEN para o SPA antes do login. */
@RestController
@RequestMapping("/api/auth")
public class CsrfController {

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken token) {
        token.getToken(); // materializa o token, o que grava o cookie
    }
}
