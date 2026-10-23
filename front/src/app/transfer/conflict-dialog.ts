import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { TranslocoPipe } from '@jsverse/transloco';
import { BytesPipe } from '../ui/bytes.pipe';
import { Conflict, ConflictAction } from './transfer.service';

export interface ConflictDialogData {
  conflict: Conflict;
  indice: number;
  total: number;
}

export interface ConflictAnswer {
  action: ConflictAction;
  applyToAll: boolean;
}

export const ACOES: ConflictAction[] = [
  'OVERWRITE',
  'OVERWRITE_NEWER',
  'OVERWRITE_SIZE',
  'OVERWRITE_SIZE_OR_NEWER',
  'RESUME',
  'RENAME',
  'SKIP',
];

/** "O arquivo já existe": tamanho e data dos dois lados e as 7 ações, com "aplicar a todos" (D22). */
@Component({
  selector: 'm2m-conflict-dialog',
  imports: [
    FormsModule,
    DatePipe,
    BytesPipe,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatRadioModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'transferencia.conflito.titulo' | transloco }}</h2>
    <mat-dialog-content>
      <p class="caminho">{{ data.conflict.dstPath }}</p>
      <table class="comparativo">
        <thead>
          <tr>
            <th></th>
            <th>{{ 'transferencia.conflito.tamanho' | transloco }}</th>
            <th>{{ 'transferencia.conflito.data' | transloco }}</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th>{{ 'transferencia.conflito.origem' | transloco }}</th>
            <td>{{ data.conflict.srcSize | bytes }}</td>
            <td>{{ data.conflict.srcMtime | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
          </tr>
          <tr>
            <th>{{ 'transferencia.conflito.destino' | transloco }}</th>
            <td>{{ data.conflict.dstSize | bytes }}</td>
            <td>{{ data.conflict.dstMtime | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
          </tr>
        </tbody>
      </table>
      <mat-radio-group [ngModel]="acao()" (ngModelChange)="acao.set($event)" class="acoes">
        @for (a of acoes; track a) {
          <mat-radio-button [value]="a">{{
            'transferencia.conflito.acao.' + a | transloco
          }}</mat-radio-button>
        }
      </mat-radio-group>
      <mat-checkbox [ngModel]="todos()" (ngModelChange)="todos.set($event)">{{
        'transferencia.conflito.aplicar-a-todos' | transloco
      }}</mat-checkbox>
      <p class="progresso">
        {{ 'transferencia.conflito.progresso' | transloco: { n: data.indice, total: data.total } }}
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button type="button" (click)="ok()">
        {{ 'comum.confirmar' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .caminho {
      font-family: monospace;
      word-break: break-all;
    }
    .comparativo {
      border-collapse: collapse;
      margin-bottom: 0.75rem;
    }
    .comparativo th,
    .comparativo td {
      padding: 0.25rem 1rem 0.25rem 0;
      text-align: left;
    }
    .acoes {
      display: flex;
      flex-direction: column;
      margin-bottom: 0.5rem;
    }
    .progresso {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85em;
    }
  `,
})
export class ConflictDialog {
  readonly data = inject<ConflictDialogData>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<ConflictDialog, ConflictAnswer>);
  readonly acoes = ACOES;
  readonly acao = signal<ConflictAction>('OVERWRITE_NEWER');
  readonly todos = signal(false);

  ok(): void {
    this.ref.close({ action: this.acao(), applyToAll: this.todos() });
  }
}
