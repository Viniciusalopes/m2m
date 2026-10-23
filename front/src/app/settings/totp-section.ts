import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';

interface TotpStatus {
  enabled: boolean;
  setupPending: boolean;
  recoveryCodesLeft: number;
}

/** Seção "Segundo fator (TOTP)" das Configurações: ativação com QR code, códigos de recuperação e desativação (D31). */
@Component({
  selector: 'm2m-totp-section',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    TranslocoPipe,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header
        ><mat-card-title>{{ 'config.totp.titulo' | transloco }}</mat-card-title></mat-card-header
      >
      <mat-card-content>
        <p>{{ 'config.totp.explicacao' | transloco }}</p>
        @if (erro(); as e) {
          <p class="erro" role="alert">
            <mat-icon aria-hidden="true">error</mat-icon>
            {{ 'erro.' + e.codigo | transloco: e.parametros }}
          </p>
        }

        @if (codigos(); as lista) {
          <div class="recuperacao" role="status">
            <p class="aviso">
              <mat-icon aria-hidden="true">warning</mat-icon
              >{{ 'config.totp.guarde-os-codigos' | transloco }}
            </p>
            <ul class="codigos">
              @for (c of lista; track c) {
                <li>{{ c }}</li>
              }
            </ul>
            <div class="acoes">
              <button mat-stroked-button type="button" (click)="baixarCodigos(lista)">
                <mat-icon>download</mat-icon>{{ 'config.totp.baixar-codigos' | transloco }}
              </button>
              <button mat-flat-button type="button" (click)="codigos.set(null)">
                {{ 'config.totp.guardei' | transloco }}
              </button>
            </div>
          </div>
        } @else if (status()?.enabled) {
          <p class="ok">
            <mat-icon aria-hidden="true">verified_user</mat-icon>
            {{ 'config.totp.ativo' | transloco: { n: status()?.recoveryCodesLeft } }}
          </p>
          <form [formGroup]="desativacao" (ngSubmit)="desativar()" class="form">
            <mat-form-field appearance="outline">
              <mat-label>{{ 'config.totp.senha' | transloco }}</mat-label>
              <input
                matInput
                type="password"
                formControlName="password"
                autocomplete="current-password"
              />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>{{ 'config.totp.codigo' | transloco }}</mat-label>
              <input matInput formControlName="code" autocomplete="one-time-code" />
            </mat-form-field>
            <div class="acoes">
              <button mat-stroked-button type="submit" [disabled]="desativacao.invalid">
                {{ 'config.totp.desativar' | transloco }}
              </button>
            </div>
          </form>
        } @else if (configuracao(); as c) {
          <p>{{ 'config.totp.escaneie' | transloco }}</p>
          <img class="qr" [src]="qr()" [alt]="'config.totp.qr-alt' | transloco" />
          <p class="segredo">
            {{ 'config.totp.segredo' | transloco }}: <code>{{ c.secret }}</code>
          </p>
          <form [formGroup]="ativacao" (ngSubmit)="ativar()" class="form">
            <mat-form-field appearance="outline">
              <mat-label>{{ 'config.totp.codigo-de-6-digitos' | transloco }}</mat-label>
              <input
                matInput
                formControlName="code"
                autocomplete="one-time-code"
                inputmode="numeric"
              />
            </mat-form-field>
            <div class="acoes">
              <button mat-button type="button" (click)="configuracao.set(null)">
                {{ 'comum.cancelar' | transloco }}
              </button>
              <button mat-flat-button type="submit" [disabled]="ativacao.invalid">
                {{ 'config.totp.confirmar-e-ativar' | transloco }}
              </button>
            </div>
          </form>
        } @else {
          <div class="acoes">
            <button mat-flat-button type="button" (click)="comecar()">
              {{ 'config.totp.ativar' | transloco }}
            </button>
          </div>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      max-width: 22.5rem;
    }
    .acoes {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
      margin-top: 0.5rem;
    }
    .qr {
      width: 13.75rem;
      height: 13.75rem;
      background: #fff;
      padding: 0.5rem;
      border-radius: 0.5rem;
    }
    .codigos {
      columns: 2;
      list-style: none;
      font-family: monospace;
      font-size: 1.1em;
      padding: 0;
    }
    .aviso,
    .ok,
    .erro {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .aviso {
      color: var(--m2m-warn);
    }
    .ok {
      color: var(--m2m-ok);
    }
    .erro {
      color: var(--m2m-fail);
    }
  `,
})
export class TotpSection implements OnInit {
  private http = inject(HttpClient);
  private fb = inject(FormBuilder);

  readonly status = signal<TotpStatus | null>(null);
  readonly configuracao = signal<{ secret: string; otpauthUri: string } | null>(null);
  readonly qr = signal('');
  readonly codigos = signal<string[] | null>(null);
  readonly erro = signal<ApiErro | null>(null);
  readonly ativacao = this.fb.nonNullable.group({ code: ['', Validators.required] });
  readonly desativacao = this.fb.nonNullable.group({
    password: ['', Validators.required],
    code: ['', Validators.required],
  });

  async ngOnInit(): Promise<void> {
    await this.carregar();
  }

  private async carregar(): Promise<void> {
    this.status.set(await firstValueFrom(this.http.get<TotpStatus>('/api/me/2fa')));
  }

  async comecar(): Promise<void> {
    this.erro.set(null);
    try {
      const c = await firstValueFrom(
        this.http.post<{ secret: string; otpauthUri: string }>('/api/me/2fa/setup', null),
      );
      // a biblioteca do QR code só é baixada quando alguém ativa o segundo fator
      // (módulo CommonJS: dependendo do empacotador, as funções vêm em `default`)
      const modulo = await import('qrcode');
      const qrcode = (modulo as unknown as { default?: typeof modulo }).default ?? modulo;
      this.qr.set(await qrcode.toDataURL(c.otpauthUri, { margin: 1, width: 220 }));
      this.configuracao.set(c);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async ativar(): Promise<void> {
    this.erro.set(null);
    try {
      const r = await firstValueFrom(
        this.http.post<{ recoveryCodes: string[] }>(
          '/api/me/2fa/enable',
          this.ativacao.getRawValue(),
        ),
      );
      this.codigos.set(r.recoveryCodes);
      this.configuracao.set(null);
      this.ativacao.reset();
      await this.carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async desativar(): Promise<void> {
    this.erro.set(null);
    try {
      await firstValueFrom(
        this.http.post<void>('/api/me/2fa/disable', this.desativacao.getRawValue()),
      );
      this.desativacao.reset();
      await this.carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  baixarCodigos(lista: string[]): void {
    const url = URL.createObjectURL(new Blob([lista.join('\n') + '\n'], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'm2m-codigos-de-recuperacao.txt';
    a.click();
    URL.revokeObjectURL(url);
  }
}
