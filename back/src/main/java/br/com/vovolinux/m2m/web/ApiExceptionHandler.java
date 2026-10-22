package br.com.vovolinux.m2m.web;

import br.com.vovolinux.m2m.files.GatewayException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> negocio(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.codigo(), e.parametros()));
    }

    /** Falhas de acesso a arquivos/máquinas viram códigos estáveis; detalhes técnicos ficam no log. */
    @ExceptionHandler(GatewayException.class)
    ResponseEntity<ApiError> gateway(GatewayException e) {
        String c = e.codigo();
        HttpStatus status = c.startsWith("identidade.") ? HttpStatus.CONFLICT
                : c.equals("local.fora-das-raizes") || c.equals("local.caminho-invalido") ? HttpStatus.FORBIDDEN
                : c.equals("local.desativado") ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ApiError(c, e.parametros()));
    }

    @ExceptionHandler(NoSuchFileException.class)
    ResponseEntity<ApiError> naoEncontrado(NoSuchFileException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("arquivo.nao-encontrado", Map.of("caminho", String.valueOf(e.getFile()))));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> semPermissao(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("arquivo.sem-permissao", Map.of("caminho", String.valueOf(e.getFile()))));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validacao(MethodArgumentNotValidException e) {
        Map<String, Object> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.put(f.getField(), f.getCode()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("validacao.invalida", campos));
    }
}
