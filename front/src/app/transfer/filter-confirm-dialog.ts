import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { PlanRequest, TransferService } from './transfer.service';

/**
 * Com filtro ativo no painel de origem: o usuário decide se o filtro vale também para a transferência e vê quantos
 * itens serão ignorados antes de confirmar (D22).
 */
@Component({
  selector: 'm2m-filter-confirm-dialog',
  imports: [
    FormsModule,
    DecimalPipe,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'transferencia.filtro.confirmar-titulo' | transloco }}</h2>
    <mat-dialog-content>
      <p>{{ 'transferencia.filtro.confirmar-explicacao' | transloco }}</p>
      <mat-checkbox [ngModel]="aplicar()" (ngModelChange)="mudar($event)">{{
        'transferencia.filtro.aplicar-a-transferencia' | transloco
      }}</mat-checkbox>
      @if (aplicar() && ignorados() !== null) {
        <p class="aviso" role="status">
          {{
            'transferencia.filtro.ignorados'
              | transloco: { n: (ignorados() ?? 0 | number: '1.0-0' : 'pt-BR') }
          }}
        </p>
      }
      @if (erro(); as e) {
        <p class="aviso" role="alert">{{ 'erro.' + e.codigo | transloco: e.parametros }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button type="button" (click)="ok()">
        {{ 'transferencia.iniciar' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .aviso {
      color: var(--m2m-warn);
    }
  `,
})
export class FilterConfirmDialog {
  private data = inject<{ request: PlanRequest }>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<FilterConfirmDialog, boolean>);
  private service = inject(TransferService);

  readonly aplicar = signal(false);
  readonly ignorados = signal<number | null>(null);
  readonly erro = signal<ApiErro | null>(null);

  async mudar(v: boolean): Promise<void> {
    this.aplicar.set(v);
    this.erro.set(null);
    if (v) {
      try {
        const p = await this.service.plan({ ...this.data.request, applyFilters: true });
        this.ignorados.set(p.ignoredByFilter);
      } catch (e) {
        this.erro.set(apiErro(e));
      }
    }
  }

  ok(): void {
    this.ref.close(this.aplicar());
  }
}
