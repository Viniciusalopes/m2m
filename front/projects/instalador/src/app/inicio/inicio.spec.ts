import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideTransloco } from '@jsverse/transloco';
import { InstaladorApi } from '../api';
import { Verificacao } from '../modelo';
import { Quiz } from '../quiz';
import { Inicio } from './inicio';

// só o que a tela Início lê do estado
const estado = {
  bloqueia: false,
  encontrada: false,
  existente: { registro: null, diretorio: '', containers: null, volumes: null },
  padroes: { containers: { diretorio: '/home/ana/m2m' } },
};

describe('Inicio', () => {
  const prerequisitos = vi.fn();

  beforeEach(async () => {
    prerequisitos.mockReset();
    await TestBed.configureTestingModule({
      imports: [Inicio],
      providers: [
        provideTransloco({ config: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' } }),
        { provide: InstaladorApi, useValue: { prerequisitos } },
        { provide: Quiz, useValue: { estado: signal(estado), iniciar: () => undefined } },
      ],
    }).compileComponents();
  });

  async function montar(v: Verificacao) {
    prerequisitos.mockResolvedValue(v);
    const f = TestBed.createComponent(Inicio);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  function botaoInstalar(el: HTMLElement): HTMLButtonElement {
    return el.querySelector('mat-card-actions button') as HTMLButtonElement;
  }

  it('máquina não preparada: mostra o que falta, orienta o instalar.sh e bloqueia a instalação', async () => {
    const el = await montar({
      bloqueia: true,
      itens: [
        {
          id: 'docker',
          titulo: 'Docker Engine',
          estado: 'pendente',
          detalhe: 'O Docker não está instalado.',
          essencial: true,
        },
        {
          id: 'openssl',
          titulo: 'OpenSSL',
          estado: 'atendido',
          detalhe: 'Instalado.',
          essencial: true,
        },
      ],
    });
    expect(prerequisitos).toHaveBeenCalledWith('/home/ana/m2m', []);
    expect(el.textContent).toContain('O Docker não está instalado.');
    expect(el.textContent).not.toContain('OpenSSL');
    expect(el.textContent).toContain('inicio.rode-script');
    expect(el.textContent).not.toContain('sudo ');
    expect(botaoInstalar(el).disabled).toBe(true);
  });

  it('só memória ou disco abaixo do mínimo: não manda rodar o instalar.sh e bloqueia a instalação', async () => {
    const el = await montar({
      bloqueia: true,
      itens: [
        {
          id: 'disco',
          titulo: 'Espaço em disco',
          estado: 'pendente',
          detalhe:
            '9.6 GiB livres em /home/ana (necessário 16.0 GiB). Libere espaço ou aumente o disco.',
          essencial: true,
        },
      ],
    });
    expect(el.textContent).toContain('Libere espaço ou aumente o disco.');
    expect(el.textContent).toContain('inicio.sem-minimos');
    expect(el.textContent).not.toContain('inicio.rode-script');
    expect(botaoInstalar(el).disabled).toBe(true);
  });

  it('máquina pronta: não mostra a verificação e libera a instalação, mesmo sem o certutil', async () => {
    const el = await montar({
      bloqueia: false,
      itens: [
        {
          id: 'certutil',
          titulo: 'certutil',
          estado: 'opcional',
          detalhe: 'Não instalado.',
          essencial: false,
        },
      ],
    });
    expect(el.textContent).not.toContain('inicio.nao-preparada');
    expect(botaoInstalar(el).disabled).toBe(false);
  });
});
