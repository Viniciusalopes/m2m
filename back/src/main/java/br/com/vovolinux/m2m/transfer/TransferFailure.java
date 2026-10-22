package br.com.vovolinux.m2m.transfer;

import java.util.Map;

/** Falha de uma transferência com código traduzível (vira {@code error_code}/{@code error_params} do item). */
public class TransferFailure extends RuntimeException {

    private final String codigo;
    private final Map<String, Object> parametros;

    public TransferFailure(String codigo) {
        this(codigo, Map.of());
    }

    public TransferFailure(String codigo, Map<String, Object> parametros) {
        super(codigo);
        this.codigo = codigo;
        this.parametros = parametros;
    }

    public String codigo() {
        return codigo;
    }

    public Map<String, Object> parametros() {
        return parametros;
    }
}
