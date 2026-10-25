import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideTransloco } from '@jsverse/transloco';
import { NEVER } from 'rxjs';
import { InstaladorApi } from '../api';
import { Situacao, TipoCertificado } from '../modelo';
import { Quiz } from '../quiz';
import { Progresso } from './progresso';

function concluida(certificado: TipoCertificado): Situacao {
  return {
    passos: [{ id: 'subir', titulo: 'Subir', estado: 'concluido', log: null }],
    rodando: false,
    resultado: {
      url: 'https://192.168.0.10',
      usuario: 'admin',
      diretorio: '/home/ana/m2m',
      urlGrafana: 'https://127.0.0.1:3000',
      avisos: [],
      certificado,
    },
  };
}

describe('Progresso: CA local na conclusão', () => {
  async function montar(certificado: TipoCertificado, remoto: boolean, caAutomatica = false) {
    await TestBed.configureTestingModule({
      imports: [Progresso],
      providers: [
        provideTransloco({ config: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' } }),
        {
          provide: InstaladorApi,
          useValue: {
            eventos: () => NEVER,
            situacao: () => Promise.resolve(concluida(certificado)),
          },
        },
        { provide: Quiz, useValue: { estado: signal({ remoto, caAutomatica, minExportar: 12 }) } },
      ],
    }).compileComponents();
    const f = TestBed.createComponent(Progresso);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('quiz pelo túnel SSH: destaca a CA para o computador do navegador', async () => {
    const el = await montar('ca-local', true);
    const ca = el.querySelector('section.ca')!;
    expect(ca.classList).toContain('destaque');
    expect(ca.textContent).toContain('conclusao.ca-remoto');
    const link = ca.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/ca');
    expect(link.getAttribute('download')).toBe('m2m-local-ca.crt');
  });

  it('máquina com tela: a CA fica discreta, para outros computadores', async () => {
    const ca = (await montar('ca-local', false)).querySelector('section.ca')!;
    expect(ca.classList).not.toContain('destaque');
    expect(ca.textContent).toContain('conclusao.ca-outros');
  });

  it('sem a CA local, não há o que baixar', async () => {
    expect((await montar('autoassinado', true)).querySelector('section.ca')).toBeNull();
  });

  it('pelo instalar-remoto.sh: avisa a importação automática e recolhe o passo a passo', async () => {
    const ca = (await montar('ca-local', true, true)).querySelector('section.ca')!;
    expect(ca.classList).not.toContain('destaque');
    expect(ca.textContent).toContain('conclusao.ca-automatica');
    const detalhes = ca.querySelector('details')!;
    expect(detalhes.open).toBe(false);
    expect(detalhes.querySelector('a')!.getAttribute('href')).toBe('/api/ca');
  });
});
