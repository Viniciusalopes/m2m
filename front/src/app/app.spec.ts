import { TestBed } from '@angular/core/testing';
import { provideTransloco } from '@jsverse/transloco';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideTransloco({
          config: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' },
        }),
      ],
    }).compileComponents();
  });

  it('cria o componente raiz', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });
});
