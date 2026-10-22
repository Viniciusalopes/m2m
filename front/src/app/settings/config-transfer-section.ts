import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';

interface ImportPreview {
  machinesCreate: string[];
  machinesReplace: string[];
  profilesCreate: string[];
  profilesReplace: string[];
  smtp: boolean;
  sshIdentity: boolean;
  exportedProfiles: number;
}

/** Seção "Exportar e importar configuração" das Configurações: arquivo cifrado por senha (D32). */
@Component({
  selector: 'm2m-config-transfer-section',
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
        ><mat-card-title>{{
          'config.transferir.titulo' | transloco
        }}</mat-card-title></mat-card-header
      >
      <mat-card-content>
        <p>{{ 'config.transferir.explicacao' | transloco }}</p>
        @if (erro(); as e) {
          <p class="erro" role="alert">
            <mat-icon aria-hidden="true">error</mat-icon>
            {{ 'erro.' + e.codigo | transloco: e.parametros }}
          </p>
        }
        <form [formGroup]="form" class="form">
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.transferir.senha' | transloco }}</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="new-password"
            />
            <mat-hint>{{ 'config.transferir.senha-dica' | transloco }}</mat-hint>
          </mat-form-field>
          <div class="acoes">
            <button mat-stroked-button type="button" (click)="exportar()" [disabled]="form.invalid">
              <mat-icon>download</mat-icon>{{ 'config.transferir.exportar' | transloco }}
            </button>
            <button mat-stroked-button type="button" (click)="arquivo.click()">
              <mat-icon>upload_file</mat-icon>{{ 'config.transferir.escolher' | transloco }}
            </button>
            <input
              #arquivo
              type="file"
              hidden
              accept=".m2mcfg,application/json"
              (change)="escolher($event)"
            />
          </div>
          @if (nome()) {
            <p>{{ 'config.transferir.arquivo' | transloco: { nome: nome() } }}</p>
            <div class="acoes">
              <button mat-stroked-button type="button" (click)="previa()" [disabled]="form.invalid">
                {{ 'config.transferir.previsualizar' | transloco }}
              </button>
            </div>
          }
        </form>
        @if (preview(); as p) {
          <ul>
            <li>
              {{ 'config.transferir.maquinas-criar' | transloco }}:
              {{ p.machinesCreate.join(', ') || '—' }}
            </li>
            <li>
              {{ 'config.transferir.maquinas-substituir' | transloco }}:
              {{ p.machinesReplace.join(', ') || '—' }}
            </li>
            <li>
              {{ 'config.transferir.perfis-criar' | transloco }}:
              {{ p.profilesCreate.join(', ') || '—' }}
            </li>
            <li>
              {{ 'config.transferir.perfis-substituir' | transloco }}:
              {{ p.profilesReplace.join(', ') || '—' }}
            </li>
            @if (p.sshIdentity) {
              <li>{{ 'config.transferir.chave-ssh' | transloco }}</li>
            }
            @if (p.smtp) {
              <li>{{ 'config.transferir.smtp' | transloco }}</li>
            }
          </ul>
          <div class="acoes">
            <button mat-flat-button type="button" (click)="importar()">
              {{ 'config.transferir.importar' | transloco }}
            </button>
          </div>
        }
        @if (importado()) {
          <p class="ok">
            <mat-icon aria-hidden="true">check_circle</mat-icon>
            {{ 'config.transferir.importado' | transloco }}
          </p>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .acoes {
      display: flex;
      gap: 8px;
      justify-content: flex-end;
      flex-wrap: wrap;
    }
    .ok,
    .erro {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .ok {
      color: var(--m2m-ok);
    }
    .erro {
      color: var(--m2m-fail);
    }
  `,
})
export class ConfigTransferSection {
  private http = inject(HttpClient);

  readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(8)]],
  });
  readonly erro = signal<ApiErro | null>(null);
  readonly nome = signal('');
  readonly preview = signal<ImportPreview | null>(null);
  readonly importado = signal(false);
  private conteudo = '';

  async exportar(): Promise<void> {
    this.erro.set(null);
    try {
      const texto = await firstValueFrom(
        this.http.post('/api/config/export', this.form.getRawValue(), { responseType: 'text' }),
      );
      const url = URL.createObjectURL(new Blob([texto], { type: 'application/json' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = 'm2m-configuracao.m2mcfg';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async escolher(ev: Event): Promise<void> {
    const f = (ev.target as HTMLInputElement).files?.[0];
    this.preview.set(null);
    this.importado.set(false);
    if (!f) {
      return;
    }
    this.conteudo = await f.text();
    this.nome.set(f.name);
  }

  async previa(): Promise<void> {
    this.erro.set(null);
    this.importado.set(false);
    try {
      this.preview.set(
        await firstValueFrom(
          this.http.post<ImportPreview>('/api/config/import/preview', {
            password: this.form.controls.password.value,
            content: this.conteudo,
          }),
        ),
      );
    } catch (e) {
      this.preview.set(null);
      this.erro.set(apiErro(e));
    }
  }

  async importar(): Promise<void> {
    this.erro.set(null);
    try {
      await firstValueFrom(
        this.http.post<ImportPreview>('/api/config/import', {
          password: this.form.controls.password.value,
          content: this.conteudo,
        }),
      );
      this.preview.set(null);
      this.importado.set(true);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
