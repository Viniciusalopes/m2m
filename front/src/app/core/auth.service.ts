import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Me {
  id: number;
  username: string;
  email: string;
  role: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);

  private readonly _me = signal<Me | null>(null);
  private readonly _loaded = signal(false);

  readonly me = this._me.asReadonly();
  readonly authenticated = computed(() => this._me() !== null);
  readonly loaded = this._loaded.asReadonly();

  /** Descobre se já existe sessão (cookie) ao abrir o app. */
  async restore(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.http.get<Me>('/api/me'));
      this._me.set(me);
    } catch {
      this._me.set(null);
    }
    this._loaded.set(true);
    return this._me();
  }

  async login(username: string, password: string): Promise<void> {
    // O GET garante o cookie XSRF-TOKEN antes do POST do login
    await firstValueFrom(this.http.get('/api/auth/csrf'));
    const body = new URLSearchParams({ username, password });
    await firstValueFrom(
      this.http.post('/api/auth/login', body.toString(), {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      }),
    );
    await this.restore();
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', null));
    } finally {
      this._me.set(null);
    }
  }

  /** Chamado pelo interceptor quando a API responde 401. */
  sessionExpired(): void {
    this._me.set(null);
  }
}
