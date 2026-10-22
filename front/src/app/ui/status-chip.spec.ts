import { TestBed } from '@angular/core/testing';
import { provideTransloco } from '@jsverse/transloco';
import { StatusChip } from './status-chip';

describe('StatusChip', () => {
  it('mostra ícone e texto, nunca só cor', async () => {
    await TestBed.configureTestingModule({
      imports: [StatusChip],
      providers: [
        provideTransloco({ config: { availableLangs: ['pt-BR'], defaultLang: 'pt-BR' } }),
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(StatusChip);
    fixture.componentRef.setInput('estado', 'fail');
    fixture.componentRef.setInput('texto', 'Falhou');
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('mat-icon')?.textContent?.trim()).toBe('error');
    expect(el.textContent).toContain('Falhou');
    expect(el.querySelector('.chip')?.classList.contains('fail')).toBe(true);
  });
});
