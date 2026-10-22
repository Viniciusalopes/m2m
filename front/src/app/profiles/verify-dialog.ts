import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { Profile } from './profiles.service';

interface Target {
  id: number;
  name: string;
  protocol: string;
}

/** Escolha do destino e do modo (rápido/completo) da verificação de igualdade. */
@Component({
  selector: 'm2m-verify-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    MatRadioModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'verificacao.titulo' | transloco: { nome: data.name } }}</h2>
    <form [formGroup]="form" (ngSubmit)="iniciar()">
      <mat-dialog-content>
        <p>{{ 'verificacao.explicacao' | transloco }}</p>
        @if (carregado() && !destinos().length) {
          <p class="aviso">{{ 'verificacao.sem-destinos' | transloco }}</p>
        }
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'verificacao.destino' | transloco }}</mat-label>
          <mat-select formControlName="destMachineId">
            @for (d of destinos(); track d.id) {
              <mat-option [value]="d.id">{{ d.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-radio-group formControlName="mode" class="modos">
          <mat-radio-button value="QUICK">{{
            'verificacao.modo-rapido' | transloco
          }}</mat-radio-button>
          <mat-radio-button value="FULL">{{
            'verificacao.modo-completo' | transloco
          }}</mat-radio-button>
        </mat-radio-group>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>
          {{ 'comum.cancelar' | transloco }}
        </button>
        <button mat-flat-button type="submit" [disabled]="form.invalid">
          {{ 'verificacao.iniciar' | transloco }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .campo {
      width: 100%;
    }
    .modos {
      display: flex;
      flex-direction: column;
    }
    .aviso {
      color: var(--m2m-warn);
    }
  `,
})
export class VerifyDialog implements OnInit {
  readonly data = inject<Profile>(MAT_DIALOG_DATA);
  private ref = inject(
    MatDialogRef<VerifyDialog, { destMachineId: number; mode: 'QUICK' | 'FULL' }>,
  );
  private http = inject(HttpClient);

  readonly destinos = signal<Target[]>([]);
  readonly carregado = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    destMachineId: [0, Validators.min(1)],
    mode: ['QUICK' as 'QUICK' | 'FULL'],
  });

  async ngOnInit(): Promise<void> {
    const t = await firstValueFrom(
      this.http.get<Target[]>(`/api/profiles/${this.data.id}/verify-targets`),
    );
    this.destinos.set(t);
    this.carregado.set(true);
    if (t.length) {
      this.form.controls.destMachineId.setValue(t[0].id);
    }
  }

  iniciar(): void {
    if (this.form.valid) {
      this.ref.close(this.form.getRawValue());
    }
  }
}
