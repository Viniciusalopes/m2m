package br.com.vovolinux.m2m.web;

import java.util.Map;

/** Corpo padrão de erro da API. */
public record ApiError(String codigo, Map<String, Object> parametros) {

    public static ApiError of(String codigo) {
        return new ApiError(codigo, Map.of());
    }
}
