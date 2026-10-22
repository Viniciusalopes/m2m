package br.com.vovolinux.m2m.web;

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validacao(MethodArgumentNotValidException e) {
        Map<String, Object> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.put(f.getField(), f.getCode()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("validacao.invalida", campos));
    }
}
