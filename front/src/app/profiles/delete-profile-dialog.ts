import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { TranslocoPipe } from '@jsverse/transloco';
import { Profile } from './profiles.service';

/** Pergunta se os backups do perfil são mantidos (órfãos, ainda restauráveis) ou excluídos junto. */
@Component({
  selector: 'm2m-delete-profile-dialog',
  imports: [MatDialogModule, MatButtonModule, MatRadioModule, TranslocoPipe],
  template: `
    <h2 mat-dialog-title>{{ 'perfis.excluir.titulo' | transloco: { nome: data.name } }}</h2>
    <mat-dialog-content>
      <p>{{ 'perfis.excluir.explicacao' | transloco }}</p>
      <mat-radio-group [value]="manter()" (change)="manter.set($event.value)" class="opcoes">
        <mat-radio-button [value]="true">{{
          'perfis.excluir.manter' | transloco
        }}</mat-radio-button>
        <mat-radio-button [value]="false">{{
          'perfis.excluir.apagar' | transloco
        }}</mat-radio-button>
      </mat-radio-group>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button class="perigo" (click)="ref.close(manter())">
        {{ 'comum.excluir' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .opcoes {
      display: flex;
      flex-direction: column;
    }
    .perigo {
      --mat-button-filled-container-color: var(--m2m-fail);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class DeleteProfileDialog {
  readonly data = inject<Profile>(MAT_DIALOG_DATA);
  readonly ref = inject(MatDialogRef<DeleteProfileDialog, boolean>);
  readonly manter = signal(true);
}
