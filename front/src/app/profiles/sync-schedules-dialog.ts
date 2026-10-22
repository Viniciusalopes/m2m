import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine, MachinesService } from '../machines/machines.service';
import { SyncSchedule, SyncService } from '../sync/sync.service';
import { Profile } from './profiles.service';

/** Agendamentos diários de sincronização de um perfil (um por destino). */
@Component({
  selector: 'm2m-sync-schedules-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>
      {{ 'sincronizacao.agendamentos.titulo' | transloco: { nome: data.name } }}
    </h2>
    <mat-dialog-content>
      <p>{{ 'sincronizacao.agendamentos.explicacao' | transloco }}</p>
      @if (erro(); as e) {
        <p class="aviso" role="alert">{{ 'erro.' + e.codigo | transloco: e.parametros }}</p>
      }
      <ul class="lista">
        @for (s of agendamentos(); track s.id) {
          <li>
            <span>
              {{ s.destMachineName }} — {{ s.time }}
              {{
                (s.enabled
                  ? 'sincronizacao.agendamentos.ativo'
                  : 'sincronizacao.agendamentos.inativo'
                ) | transloco
              }}
              ·
              {{
                'sincronizacao.agendamentos.limite'
                  | transloco: { pct: s.maxDeletionsPercent, abs: s.maxDeletionsAbs }
              }}
            </span>
            <button
              mat-icon-button
              type="button"
              (click)="remover(s)"
              [attr.aria-label]="'comum.excluir' | transloco"
            >
              <mat-icon>delete</mat-icon>
            </button>
          </li>
        } @empty {
          <li>{{ 'sincronizacao.agendamentos.vazio' | transloco }}</li>
        }
      </ul>
      <h3>{{ 'sincronizacao.agendamentos.novo' | transloco }}</h3>
      <form [formGroup]="form" (ngSubmit)="salvar()">
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'sincronizacao.destino' | transloco }}</mat-label>
          <mat-select formControlName="destMachineId">
            @for (d of destinos(); track d.id) {
              <mat-option [value]="d.id">{{ d.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'sincronizacao.agendamentos.horario' | transloco }}</mat-label>
          <input matInput type="time" formControlName="time" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'sincronizacao.agendamentos.limite-pct' | transloco }}</mat-label>
          <input matInput type="number" min="0" max="100" formControlName="maxDeletionsPercent" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'sincronizacao.agendamentos.limite-abs' | transloco }}</mat-label>
          <input matInput type="number" min="0" formControlName="maxDeletionsAbs" />
        </mat-form-field>
        <mat-checkbox formControlName="enabled">{{
          'sincronizacao.agendamentos.ativar' | transloco
        }}</mat-checkbox>
        <p class="dica">{{ 'sincronizacao.agendamentos.dica-manual' | transloco }}</p>
        <button mat-flat-button type="submit" [disabled]="form.invalid">
          {{ 'comum.salvar' | transloco }}
        </button>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'comum.fechar' | transloco }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .campo {
      width: 100%;
    }
    .aviso {
      color: var(--m2m-warn);
    }
    .lista {
      list-style: none;
      padding: 0;
    }
    .lista li {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .dica {
      font-size: 0.85em;
    }
  `,
})
export class SyncSchedulesDialog implements OnInit {
  readonly data = inject<Profile>(MAT_DIALOG_DATA);
  private machines = inject(MachinesService);
  private sync = inject(SyncService);

  readonly agendamentos = signal<SyncSchedule[]>([]);
  readonly destinos = signal<Machine[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  readonly form = inject(FormBuilder).nonNullable.group({
    destMachineId: [0, Validators.min(1)],
    time: ['03:00', Validators.required],
    maxDeletionsPercent: [20, [Validators.min(0), Validators.max(100)]],
    maxDeletionsAbs: [500, Validators.min(0)],
    enabled: [false],
  });

  async ngOnInit(): Promise<void> {
    const todas = await this.machines.list();
    const ok = todas.filter(
      (m) =>
        m.id !== this.data.sourceMachineId &&
        this.data.directories.length > 0 &&
        this.data.directories.every((d) => d.mappings.some((x) => x.machineId === m.id)),
    );
    this.destinos.set(ok);
    if (ok.length) {
      this.form.controls.destMachineId.setValue(ok[0].id);
    }
    await this.carregar();
  }

  private async carregar(): Promise<void> {
    this.agendamentos.set(await this.sync.schedules(this.data.id));
  }

  async salvar(): Promise<void> {
    this.erro.set(null);
    const v = this.form.getRawValue();
    const existente = this.agendamentos().find((s) => s.destMachineId === v.destMachineId);
    try {
      if (existente) {
        await this.sync.updateSchedule(this.data.id, existente.id, v);
      } else {
        await this.sync.createSchedule(this.data.id, v);
      }
      await this.carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async remover(s: SyncSchedule): Promise<void> {
    this.erro.set(null);
    try {
      await this.sync.deleteSchedule(this.data.id, s.id);
      await this.carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
