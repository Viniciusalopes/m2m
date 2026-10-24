import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { About } from './about';
import { formasDoacao } from './doacao';

const sobre = {
  versao: 'Versão {{ versao }}',
  licenca: 'Copyright (c) 2026 Vovolinux. Distribuído sob a licença MIT.',
  'doacao-titulo': 'Apoie o projeto',
  'doacao-convite': 'O m2m é gratuito e de código aberto.',
  'doacao-em-breve': 'As formas de doação serão publicadas em breve.',
  'doacao-abrir': 'Doar',
  'doacao-copiar': 'Copiar',
  'doacao-copiado': 'Copiado!',
};

async function abrir(doacao: object | 'erro'): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    imports: [
      About,
      TranslocoTestingModule.forRoot({
        langs: { 'pt-BR': { app: { nome: 'm2m' }, sobre } },
        translocoConfig: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' },
      }),
    ],
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  const fixture = TestBed.createComponent(About);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('assets/THIRD-PARTY-NOTICES.md').flush('avisos');
  const req = http.expectOne('assets/doacao.json');
  if (doacao === 'erro') {
    req.flush('não encontrado', { status: 404, statusText: 'Not Found' });
  } else {
    req.flush(doacao);
  }
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('About', () => {
  it('informa a licença MIT', async () => {
    const el = await abrir({ formas: [] });
    expect(el.textContent).toContain('Distribuído sob a licença MIT.');
  });

  it('sem formas de doação mostra o convite e o aviso de em breve', async () => {
    const el = await abrir({ formas: [] });
    const cartao = el.querySelector('.doacao')!;
    expect(cartao.textContent).toContain('Apoie o projeto');
    expect(cartao.textContent).toContain('O m2m é gratuito e de código aberto.');
    expect(cartao.textContent).toContain('serão publicadas em breve');
    expect(cartao.querySelector('a')).toBeNull();
  });

  it('mostra o link em nova aba e o Pix com botão de copiar', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const el = await abrir({
      formas: [
        {
          tipo: 'link',
          nome: 'PayPal',
          descricao: 'Cartão ou saldo PayPal',
          valor: 'https://www.paypal.com/donate/?hosted_button_id=X',
        },
        {
          tipo: 'pix',
          nome: 'Pix',
          descricao: 'Chave aleatória',
          valor: '123e4567-e89b-12d3-a456-426614174000',
        },
      ],
    });
    const link = el.querySelector<HTMLAnchorElement>('.doacao a')!;
    expect(link.href).toBe('https://www.paypal.com/donate/?hosted_button_id=X');
    expect(link.target).toBe('_blank');
    expect(link.rel).toBe('noopener noreferrer');
    expect(el.querySelector('.doacao .valor')!.textContent).toContain(
      '123e4567-e89b-12d3-a456-426614174000',
    );
    expect(el.querySelector('.doacao')!.textContent).not.toContain('em breve');

    el.querySelector<HTMLButtonElement>('.doacao button')!.click();
    await Promise.resolve();
    expect(writeText).toHaveBeenCalledWith('123e4567-e89b-12d3-a456-426614174000');
  });

  it('arquivo de doações ausente mostra só o convite', async () => {
    const el = await abrir('erro');
    const cartao = el.querySelector('.doacao')!;
    expect(cartao.textContent).toContain('O m2m é gratuito e de código aberto.');
    expect(cartao.querySelector('a, button')).toBeNull();
  });
});

describe('formasDoacao', () => {
  it('descarta formato inválido e links que não são https', () => {
    expect(formasDoacao(null)).toEqual([]);
    expect(formasDoacao({ formas: 'x' })).toEqual([]);
    expect(
      formasDoacao({
        formas: [
          { tipo: 'link', nome: 'Ruim', valor: 'javascript:alert(1)' },
          { tipo: 'link', nome: 'Http', valor: 'http://exemplo.test' },
          { tipo: 'cripto', nome: 'Outro', valor: 'abc' },
          { tipo: 'pix', nome: '', valor: 'chave' },
          { tipo: 'pix', nome: 'Pix', valor: 'chave' },
        ],
      }),
    ).toEqual([{ tipo: 'pix', nome: 'Pix', descricao: '', valor: 'chave' }]);
  });
});
