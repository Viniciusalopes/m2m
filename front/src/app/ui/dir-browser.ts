import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { BrowseResult, MachinesService } from '../machines/machines.service';

export interface DirBrowserData {
  machineId: number;
  machineName: string;
  startPath?: string | null;
}

/** Navegador de diretórios de uma máquina: devolve o diretório escolhido (só pastas são listadas). */
@Component({
  selector: 'm2m-dir-browser',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatProgressBarModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'navegador.titulo' | transloco: { maquina: data.machineName } }}</h2>
    <mat-dialog-content>
      @if (carregando()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (erro(); as e) {
        <p class="erro" role="alert">
          <mat-icon aria-hidden="true">error</mat-icon>
          {{ 'erro.' + e.codigo | transloco: e.parametros }}
        </p>
      }
      @if (resultado(); as r) {
        <p class="caminho">
          <mat-icon aria-hidden="true">folder_open</mat-icon>
          <span>{{ r.path }}</span>
        </p>
        <mat-nav-list dense>
          @if (r.parent) {
            <a mat-list-item (click)="ir(r.parent)">
              <mat-icon matListItemIcon>arrow_upward</mat-icon>
              <span matListItemTitle>..</span>
            </a>
          }
          @for (item of r.items; track item.path) {
            <a mat-list-item (click)="ir(item.path)">
              <mat-icon matListItemIcon>folder</mat-icon>
              <span matListItemTitle>{{ item.name }}</span>
            </a>
          } @empty {
            <p class="vazio">{{ 'navegador.vazio' | transloco }}</p>
          }
        </mat-nav-list>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button [disabled]="!resultado()" (click)="escolher()">
        {{ 'navegador.escolher' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content {
      min-width: min(32.5rem, 80vw);
      max-height: 60vh;
    }
    .caminho {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-family: ui-monospace, monospace;
      word-break: break-all;
    }
    .erro {
      color: var(--m2m-fail);
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .vazio {
      color: var(--mat-sys-on-surface-variant);
      padding: 0.5rem 1rem;
    }
  `,
})
export class DirBrowser implements OnInit {
  private service = inject(MachinesService);
  private ref = inject(MatDialogRef<DirBrowser, string>);
  readonly data = inject<DirBrowserData>(MAT_DIALOG_DATA);

  readonly resultado = signal<BrowseResult | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<ApiErro | null>(null);

  ngOnInit(): void {
    void this.ir(this.data.startPath ?? undefined);
  }

  async ir(path?: string): Promise<void> {
    this.carregando.set(true);
    this.erro.set(null);
    try {
      this.resultado.set(await this.service.browse(this.data.machineId, path, true));
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.carregando.set(false);
    }
  }

  escolher(): void {
    const r = this.resultado();
    if (r) {
      this.ref.close(r.path);
    }
  }
}
