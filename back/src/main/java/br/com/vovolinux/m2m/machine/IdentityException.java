package br.com.vovolinux.m2m.machine;

import br.com.vovolinux.m2m.files.GatewayException;
import java.util.HashMap;
import java.util.Map;

/** A identidade do servidor não está confirmada ou mudou: a conexão é recusada até o usuário confirmar. */
public class IdentityException extends GatewayException {

    public static final String CONFIRMACAO_NECESSARIA = "identidade.confirmacao-necessaria";
    public static final String ALTERADA = "identidade.alterada";

    public IdentityException(String codigo, String fingerprint, String confirmada) {
        super(codigo, "Identidade do servidor: " + codigo, params(fingerprint, confirmada), null);
    }

    private static Map<String, Object> params(String fingerprint, String confirmada) {
        Map<String, Object> p = new HashMap<>();
        p.put("fingerprint", fingerprint);
        if (confirmada != null) {
            p.put("confirmada", confirmada);
        }
        return p;
    }
}
