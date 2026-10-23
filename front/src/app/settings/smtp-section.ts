import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';

interface SmtpView {
  host: string;
  port: number;
  security: 'NONE' | 'STARTTLS' | 'SSL';
  username: string;
  hasPassword: boolean;
  fromAddress: string;
  enabled: boolean;
  configured: boolean;
}

/** Seção "E-mail (SMTP)" das Configurações: cada usuário cadastra o próprio servidor (D27). */
@Component({
  selector: 'm2m-smtp-section',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatIconModule,
    TranslocoPipe,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header
        ><mat-card-title>{{ 'config.smtp.titulo' | transloco }}</mat-card-title></mat-card-header
      >
      <mat-card-content>
        <p>{{ 'config.smtp.explicacao' | transloco }}</p>
        <form [formGroup]="form" (ngSubmit)="salvar()" class="form">
          <div class="linha">
            <mat-form-field appearance="outline" class="host">
              <mat-label>{{ 'config.smtp.host' | transloco }}</mat-label>
              <input matInput formControlName="host" placeholder="smtp.exemplo.com" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="porta">
              <mat-label>{{ 'config.smtp.porta' | transloco }}</mat-label>
              <input matInput type="number" formControlName="port" />
            </mat-form-field>
          </div>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.smtp.seguranca' | transloco }}</mat-label>
            <mat-select formControlName="security">
              <mat-option value="NONE">{{
                'config.smtp.seguranca-nenhuma' | transloco
              }}</mat-option>
              <mat-option value="STARTTLS">STARTTLS</mat-option>
              <mat-option value="SSL">SSL/TLS</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.smtp.usuario' | transloco }}</mat-label>
            <input matInput formControlName="username" autocomplete="off" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.smtp.senha' | transloco }}</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="new-password"
              [placeholder]="senhaSalva() ? '••••••••' : ''"
            />
            @if (senhaSalva()) {
              <mat-hint>{{ 'config.smtp.senha-salva' | transloco }}</mat-hint>
            }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.smtp.remetente' | transloco }}</mat-label>
            <input matInput formControlName="fromAddress" placeholder="m2m@exemplo.com" />
          </mat-form-field>
          <mat-slide-toggle formControlName="enabled">{{
            'config.smtp.ativo' | transloco
          }}</mat-slide-toggle>

          @if (resultado(); as r) {
            @if (r.ok) {
              <p class="ok" role="status">
                <mat-icon aria-hidden="true">check_circle</mat-icon>
                {{ 'config.smtp.teste-ok' | transloco }}
              </p>
            } @else {
              <p class="erro" role="alert">
                <mat-icon aria-hidden="true">error</mat-icon>
                {{ 'config.smtp.teste-falhou' | transloco }}: {{ r.error }}
              </p>
            }
          }
          @if (erro(); as e) {
            <p class="erro" role="alert">
              <mat-icon aria-hidden="true">error</mat-icon>
              {{ 'erro.' + e.codigo | transloco: e.parametros }}
            </p>
          }
          @if (salvo()) {
            <p class="ok" role="status">
              <mat-icon aria-hidden="true">check_circle</mat-icon>
              {{ 'config.smtp.salvo' | transloco }}
            </p>
          }

          <div class="acoes">
            <button
              mat-stroked-button
              type="button"
              (click)="testar()"
              [disabled]="form.invalid || testando()"
            >
              <mat-icon>forward_to_inbox</mat-icon>{{ 'config.smtp.enviar-teste' | transloco }}
            </button>
            <button mat-flat-button type="submit" [disabled]="form.invalid">
              {{ 'comum.salvar' | transloco }}
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 4px;
      max-width: 35rem;
    }
    .linha {
      display: flex;
      gap: 12px;
    }
    .host {
      flex: 1;
    }
    .porta {
      width: 7.5rem;
    }
    .acoes {
      display: flex;
      gap: 8px;
      justify-content: flex-end;
      margin-top: 8px;
    }
    .ok,
    .erro {
      display: flex;
      gap: 8px;
      align-items: center;
      margin: 0;
    }
    .ok {
      color: var(--m2m-ok);
    }
    .erro {
      color: var(--m2m-fail);
    }
  `,
})
export class SmtpSection implements OnInit {
  private http = inject(HttpClient);
  private fb = inject(FormBuilder);

  readonly form = this.fb.nonNullable.group({
    host: ['', Validators.required],
    port: [587, [Validators.required, Validators.min(1), Validators.max(65535)]],
    security: ['STARTTLS' as 'NONE' | 'STARTTLS' | 'SSL'],
    username: [''],
    password: [''],
    fromAddress: ['', Validators.required],
    enabled: [false],
  });
  readonly senhaSalva = signal(false);
  readonly erro = signal<ApiErro | null>(null);
  readonly salvo = signal(false);
  readonly testando = signal(false);
  readonly resultado = signal<{ ok: boolean; error: string | null } | null>(null);

  async ngOnInit(): Promise<void> {
    const v = await firstValueFrom(this.http.get<SmtpView>('/api/me/smtp'));
    this.form.patchValue({
      host: v.host,
      port: v.port,
      security: v.security,
      username: v.username ?? '',
      fromAddress: v.fromAddress,
      enabled: v.enabled,
    });
    this.senhaSalva.set(v.hasPassword);
  }

  async salvar(): Promise<void> {
    this.erro.set(null);
    this.salvo.set(false);
    this.resultado.set(null);
    try {
      const v = await firstValueFrom(
        this.http.put<SmtpView>('/api/me/smtp', this.form.getRawValue()),
      );
      this.senhaSalva.set(v.hasPassword);
      this.form.controls.password.setValue('');
      this.salvo.set(true);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  /** Envia o e-mail de teste com os dados do formulário (sem precisar salvar). */
  async testar(): Promise<void> {
    this.testando.set(true);
    this.erro.set(null);
    this.salvo.set(false);
    try {
      this.resultado.set(
        await firstValueFrom(
          this.http.post<{ ok: boolean; error: string | null }>(
            '/api/me/smtp/test',
            this.form.getRawValue(),
          ),
        ),
      );
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.testando.set(false);
    }
  }
}
