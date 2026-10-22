import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';

export interface PromptData {
  titulo: string;
  rotulo: string;
  valor?: string;
  confirmar?: string;
  params?: Record<string, unknown>;
}

/** Pede um texto (ex.: nome de pasta); devolve o texto ou {@code undefined} se cancelado. */
@Component({
  selector: 'm2m-prompt-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.titulo | transloco: data.params }}</h2>
    <form [formGroup]="form" (ngSubmit)="ok()">
      <mat-dialog-content>
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ data.rotulo | transloco }}</mat-label>
          <input matInput formControlName="valor" autofocus />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>
          {{ 'comum.cancelar' | transloco }}
        </button>
        <button mat-flat-button type="submit" [disabled]="form.invalid">
          {{ data.confirmar ?? 'comum.confirmar' | transloco }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .campo {
      width: 100%;
    }
  `,
})
export class PromptDialog {
  readonly data = inject<PromptData>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<PromptDialog, string>);
  readonly form = inject(FormBuilder).nonNullable.group({
    valor: [this.data.valor ?? '', [Validators.required, Validators.pattern(/^[^/]+$/)]],
  });

  ok(): void {
    if (this.form.valid) {
      this.ref.close(this.form.controls.valor.value.trim());
    }
  }
}
