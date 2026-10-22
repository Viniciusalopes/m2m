import { HttpErrorResponse } from '@angular/common/http';
import { apiErro } from './api-error';

describe('apiErro', () => {
  it('extrai codigo e parametros do corpo da API', () => {
    const e = new HttpErrorResponse({
      status: 423,
      error: { codigo: 'auth.conta-bloqueada', parametros: { minutos: 5 } },
    });
    expect(apiErro(e)).toEqual({ codigo: 'auth.conta-bloqueada', parametros: { minutos: 5 } });
  });

  it('usa o erro genérico quando o corpo não tem codigo', () => {
    expect(apiErro(new HttpErrorResponse({ status: 500, error: 'x' })).codigo).toBe(
      'erro.generico',
    );
    expect(apiErro(new Error('x')).codigo).toBe('erro.generico');
  });
});
