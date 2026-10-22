package br.com.vovolinux.m2m.job;

import java.util.Map;

/** Falha de negócio de um job, com código estável e parâmetros para o front. */
public class JobFailure extends RuntimeException {

    private final String codigo;
    private final Map<String, Object> parametros;

    public JobFailure(String codigo, Map<String, Object> parametros) {
        super(codigo);
        this.codigo = codigo;
        this.parametros = parametros;
    }

    public JobFailure(String codigo) {
        this(codigo, Map.of());
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, Object> parametros() {
        return parametros;
    }
}
