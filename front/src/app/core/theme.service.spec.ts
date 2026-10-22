import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('alterna sistema → claro → escuro → sistema e lembra a escolha', () => {
    const t = TestBed.inject(ThemeService);
    expect(t.mode()).toBe('system');
    t.cycle();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    t.cycle();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('m2m.theme')).toBe('dark');
    t.cycle();
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });
});
