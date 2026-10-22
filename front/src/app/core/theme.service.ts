import { Injectable, signal } from '@angular/core';

export type ThemeMode = 'system' | 'light' | 'dark';
const KEY = 'm2m.theme';

/** Tema claro/escuro: segue o sistema, com alternância manual lembrada neste navegador (D34). */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly mode = signal<ThemeMode>(this.read());

  constructor() {
    this.apply(this.mode());
  }

  cycle(): void {
    const next: ThemeMode =
      this.mode() === 'system' ? 'light' : this.mode() === 'light' ? 'dark' : 'system';
    this.set(next);
  }

  set(mode: ThemeMode): void {
    this.mode.set(mode);
    try {
      localStorage.setItem(KEY, mode);
    } catch {
      /* armazenamento indisponível: só não lembra */
    }
    this.apply(mode);
  }

  private read(): ThemeMode {
    try {
      const v = localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' ? v : 'system';
    } catch {
      return 'system';
    }
  }

  private apply(mode: ThemeMode): void {
    const root = document.documentElement;
    if (mode === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', mode);
    }
  }
}
