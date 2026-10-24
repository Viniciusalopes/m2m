import { TestBed } from '@angular/core/testing';
import { provideTransloco } from '@jsverse/transloco';
import { NEVER } from 'rxjs';
import { InstaladorApi } from '../api';
import { Localizada } from '../modelo';
import { Desinstalacao } from './desinstalacao';

const localizada: Localizada = {
  encontrada: true,
  diretorio: '/home/ana/m2m',
  versao: 'v1.0.0',
  criadoEm: '2026-10-23T10:00:00Z',
  containers: [],
  volumes: [],
  temEnv: true,
  backNoAr: true,
  itens: [
    { id: 'containers', titulo: 'Containers', descricao: '', padrao: true, sempre: true },
    {
      id: 'volumes',
      titulo: 'Volumes',
      descricao: '',
      padrao: true,
      sempre: false,
      aviso: 'apaga o banco',
    },
  ],
  destinos: {
    consultados: true,
    itens: [
      {
        perfil: 'Fotos',
        maquina: 'NOTEBOOK',
        protocolo: 'LOCAL',
        host: 'localhost',
        diretorio: '/home/bk',
      },
    ],
  },
};

describe('Desinstalacao', () => {
  const desinstalar = vi.fn().mockResolvedValue({});

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Desinstalacao],
      providers: [
        provideTransloco({ config: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' } }),
        {
          provide: InstaladorApi,
          useValue: {
            desinstalacao: () => Promise.resolve(localizada),
            eventos: () => NEVER,
            desinstalar,
            resultadoDesinstalacao: () => Promise.resolve({ rodando: false, resultado: null }),
          },
        },
      ],
    }).compileComponents();
  });

  async function montar() {
    const f = TestBed.createComponent(Desinstalacao);
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  function botaoRemover(el: HTMLElement): HTMLButtonElement {
    return el.querySelector('button.perigo') as HTMLButtonElement;
  }

  it('lista os destinos de backup e marca os itens padrão', async () => {
    const f = await montar();
    const el: HTMLElement = f.nativeElement;
    expect(el.textContent).toContain('Fotos');
    expect(el.textContent).toContain('/home/bk');
    expect([...f.componentInstance.marcados()]).toEqual(['containers', 'volumes']);
  });

  it('o botão de remover só habilita com a palavra exata', async () => {
    const f = await montar();
    const c = f.componentInstance;
    expect(botaoRemover(f.nativeElement).disabled).toBe(true);
    c.digitado.set('desinstalar');
    f.detectChanges();
    expect(botaoRemover(f.nativeElement).disabled).toBe(true);
    await c.remover();
    expect(desinstalar).not.toHaveBeenCalled();
    c.digitado.set('DESINSTALAR');
    f.detectChanges();
    expect(botaoRemover(f.nativeElement).disabled).toBe(false);
  });

  it('desmarcar os volumes tira o aviso e o item da remoção', async () => {
    const f = await montar();
    const c = f.componentInstance;
    expect(f.nativeElement.textContent).toContain('apaga o banco');
    c.marcar('volumes', false);
    f.detectChanges();
    expect(f.nativeElement.textContent).not.toContain('apaga o banco');
    expect([...c.marcados()]).toEqual(['containers']);
  });
});
