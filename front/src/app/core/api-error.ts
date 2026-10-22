import { HttpErrorResponse } from '@angular/common/http';

export interface ApiErro {
  codigo: string;
  parametros?: Record<string, unknown>;
}

/** Extrai {codigo, parametros} do corpo de erro da API (D35); códigos viram texto em `erro.<codigo>` no pt-BR.json. */
export function apiErro(err: unknown): ApiErro {
  if (err instanceof HttpErrorResponse && err.error && typeof err.error.codigo === 'string') {
    return err.error as ApiErro;
  }
  return { codigo: 'erro.generico' };
}
