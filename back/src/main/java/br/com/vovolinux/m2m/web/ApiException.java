package br.com.vovolinux.m2m.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Erro de negócio: o back devolve um código estável + parâmetros; o texto é traduzido no front (D35). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final Map<String, Object> parametros;

    public ApiException(HttpStatus status, String codigo) {
        this(status, codigo, Map.of());
    }

    public ApiException(HttpStatus status, String codigo, Map<String, Object> parametros) {
        super(codigo);
        this.status = status;
        this.codigo = codigo;
        this.parametros = parametros;
    }

    public HttpStatus status() {
        return status;
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, Object> parametros() {
        return parametros;
    }
}
