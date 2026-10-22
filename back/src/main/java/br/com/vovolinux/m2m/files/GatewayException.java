package br.com.vovolinux.m2m.files;

import java.io.IOException;
import java.util.Map;

/** Falha de acesso com código estável (traduzido no front) e parâmetros. */
public class GatewayException extends IOException {

    private final String codigo;
    private final Map<String, Object> parametros;

    public GatewayException(String codigo, String mensagem, Map<String, Object> parametros, Throwable causa) {
        super(mensagem, causa);
        this.codigo = codigo;
        this.parametros = parametros;
    }

    public GatewayException(String codigo, String mensagem) {
        this(codigo, mensagem, Map.of(), null);
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, Object> parametros() {
        return parametros;
    }
}
