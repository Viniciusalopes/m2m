import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { Profile } from './profiles.service';

/** "Backup agora" com complemento pontual de nome (opcional). */
@Component({
  selector: 'm2m-backup-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'perfis.backup.titulo' | transloco: { nome: data.name } }}</h2>
    <form [formGroup]="form" (ngSubmit)="iniciar()">
      <mat-dialog-content>
        <p>{{ 'perfis.backup.explicacao' | transloco }}</p>
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'perfis.backup.complemento' | transloco }}</mat-label>
          <input matInput formControlName="complement" />
          <mat-hint>{{ 'perfis.form.complemento-dica' | transloco }}</mat-hint>
          @if (form.controls.complement.hasError('pattern')) {
            <mat-error>{{ 'erro.perfil.complemento-invalido' | transloco }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>
          {{ 'comum.cancelar' | transloco }}
        </button>
        <button mat-flat-button type="submit" [disabled]="form.invalid">
          {{ 'perfis.backup.iniciar' | transloco }}
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
export class BackupDialog {
  readonly data = inject<Profile>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<BackupDialog, { complement: string | null }>);
  readonly form = inject(FormBuilder).nonNullable.group({
    complement: ['', Validators.pattern(/^[A-Za-z0-9_-]*$/)],
  });

  iniciar(): void {
    if (this.form.valid) {
      this.ref.close({ complement: this.form.controls.complement.value.trim() || null });
    }
  }
}
