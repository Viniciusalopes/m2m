import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { TranslocoPipe } from '@jsverse/transloco';

export interface ConfirmData {
  titulo: string;
  mensagem: string;
  confirmar?: string;
  /** Parâmetros de tradução. */
  params?: Record<string, unknown>;
  perigo?: boolean;
}

/** Confirmação genérica; todos os textos são chaves do pt-BR.json. */
@Component({
  selector: 'm2m-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule, TranslocoPipe],
  template: `
    <h2 mat-dialog-title>{{ data.titulo | transloco: data.params }}</h2>
    <mat-dialog-content>{{ data.mensagem | transloco: data.params }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button [mat-dialog-close]="true" [class.perigo]="data.perigo">
        {{ data.confirmar ?? 'comum.confirmar' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .perigo {
      --mat-button-filled-container-color: var(--m2m-fail);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class ConfirmDialog {
  readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
}
