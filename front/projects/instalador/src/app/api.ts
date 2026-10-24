import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import {
  Erros,
  Estado,
  Evento,
  Importado,
  Localizada,
  Respostas,
  ResultadoDesinstalacao,
  Segredos,
  Situacao,
  Verificacao,
} from './modelo';

/** O servidor do instalador recusa POST sem este cabeçalho (proteção contra CSRF). */
export const instaladorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.method === 'GET' ? req : req.clone({ setHeaders: { 'X-M2M-Instalador': '1' } }));

/** Mensagem de erro da API do instalador ({erro}) ou do HTTP. */
export function mensagemErro(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.error && typeof e.error.erro === 'string') {
      return e.error.erro;
    }
    if (e.status === 0) {
      return 'O instalador não respondeu. Confira se ele continua rodando no terminal.';
    }
    return `${e.status} ${e.statusText}`;
  }
  return String(e);
}

/** Baixa um conteúdo como arquivo, pelo navegador: o instalador nunca grava o arquivo em disco. */
export function salvarArquivo(conteudo: Blob, nome: string): void {
  const url = URL.createObjectURL(conteudo);
  const a = document.createElement('a');
  a.href = url;
  a.download = nome;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function nomeDoCabecalho(cd: string | null, padrao: string): string {
  const m = cd?.match(/filename="([^"]+)"/);
  return m ? m[1] : padrao;
}

@Injectable({ providedIn: 'root' })
export class InstaladorApi {
  private http = inject(HttpClient);

  estado(): Promise<Estado> {
    return firstValueFrom(this.http.get<Estado>('/api/estado'));
  }

  prerequisitos(diretorio: string, portas: number[]): Promise<Verificacao> {
    return firstValueFrom(this.http.post<Verificacao>('/api/prerequisitos', { diretorio, portas }));
  }

  async validar(etapa: string, respostas: Respostas, segredos: Segredos): Promise<Erros> {
    const r = await firstValueFrom(
      this.http.post<{ erros: Erros }>('/api/validar', { etapa, respostas, segredos }),
    );
    return r.erros ?? {};
  }

  importar(conteudo: string, senha: string): Promise<Importado> {
    return firstValueFrom(this.http.post<Importado>('/api/importar', { conteudo, senha }));
  }

  /** Monta o .json no instalador e o baixa pelo navegador. */
  async exportar(respostas: Respostas, segredos: Segredos, senha: string): Promise<void> {
    await this.baixar('/api/exportar', { respostas, segredos, senha });
  }

  async exportarInstalada(senha: string): Promise<void> {
    await this.baixar('/api/desinstalacao/exportar', { senha });
  }

  private async baixar(url: string, corpo: unknown): Promise<void> {
    const r = await firstValueFrom(
      this.http.post(url, corpo, { observe: 'response', responseType: 'blob' }),
    );
    salvarArquivo(
      r.body!,
      nomeDoCabecalho(r.headers.get('Content-Disposition'), 'm2m-instalacao.json'),
    );
  }

  instalar(
    respostas: Respostas,
    segredos: Segredos,
    restaurarConfiguracao: boolean,
  ): Promise<Situacao> {
    return firstValueFrom(
      this.http.post<Situacao>('/api/instalar', { respostas, segredos, restaurarConfiguracao }),
    );
  }

  retomar(): Promise<Situacao> {
    return firstValueFrom(this.http.post<Situacao>('/api/instalar/retomar', {}));
  }

  situacao(): Promise<Situacao> {
    return firstValueFrom(this.http.get<Situacao>('/api/instalacao'));
  }

  async atalhos(): Promise<string[]> {
    const r = await firstValueFrom(this.http.post<{ arquivos: string[] }>('/api/atalhos', {}));
    return r.arquivos;
  }

  desinstalacao(): Promise<Localizada> {
    return firstValueFrom(this.http.get<Localizada>('/api/desinstalacao'));
  }

  desinstalar(itens: string[], confirmacao: string): Promise<unknown> {
    return firstValueFrom(this.http.post('/api/desinstalar', { itens, confirmacao }));
  }

  resultadoDesinstalacao(): Promise<{
    rodando: boolean;
    resultado: ResultadoDesinstalacao | null;
  }> {
    return firstValueFrom(
      this.http.get<{ rodando: boolean; resultado: ResultadoDesinstalacao | null }>(
        '/api/desinstalacao/resultado',
      ),
    );
  }

  encerrar(): Promise<unknown> {
    return firstValueFrom(this.http.post('/api/encerrar', {}));
  }

  /** Eventos de progresso por SSE; o cookie do token vai junto (mesma origem). */
  eventos(): Observable<Evento> {
    return new Observable<Evento>((sub) => {
      const fonte = new EventSource('/api/eventos');
      fonte.onmessage = (m) => sub.next(JSON.parse(m.data) as Evento);
      return () => fonte.close();
    });
  }
}
