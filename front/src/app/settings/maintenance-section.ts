import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine, MachinesService } from '../machines/machines.service';
import { JobView } from '../profiles/profiles.service';

interface MaintenanceSettings {
  verify: { enabled: boolean; cron: string };
  dbBackup: { enabled: boolean; time: string; storageMachineId: number | null; storageDir: string };
}

/** Seções "Verificação periódica", "Backup do banco do m2m" e "Reindexar backups" das Configurações (D26, D30, D32). */
@Component({
  selector: 'm2m-maintenance-section',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatIconModule,
    TranslocoPipe,
  ],
  template: `
    @if (erro(); as e) {
      <p class="erro" role="alert">
        <mat-icon aria-hidden="true">error</mat-icon>
        {{ 'erro.' + e.codigo | transloco: e.parametros }}
      </p>
    }
    <mat-card appearance="outlined">
      <mat-card-header
        ><mat-card-title>{{
          'config.manutencao.titulo' | transloco
        }}</mat-card-title></mat-card-header
      >
      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="salvar()" class="form">
          <h3>{{ 'config.manutencao.verificacao' | transloco }}</h3>
          <p>{{ 'config.manutencao.verificacao-explicacao' | transloco }}</p>
          <mat-slide-toggle formControlName="verifyEnabled">{{
            'config.manutencao.ativar' | transloco
          }}</mat-slide-toggle>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.manutencao.cron' | transloco }}</mat-label>
            <input matInput formControlName="verifyCron" />
            <mat-hint>{{ 'config.manutencao.cron-dica' | transloco }}</mat-hint>
          </mat-form-field>

          <h3>{{ 'config.manutencao.banco' | transloco }}</h3>
          <p>{{ 'config.manutencao.banco-explicacao' | transloco }}</p>
          <mat-slide-toggle formControlName="dbEnabled">{{
            'config.manutencao.ativar' | transloco
          }}</mat-slide-toggle>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.manutencao.horario' | transloco }}</mat-label>
            <input matInput type="time" formControlName="dbTime" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.manutencao.armazenamento' | transloco }}</mat-label>
            <mat-select formControlName="dbMachine">
              <mat-option [value]="0">{{ 'config.manutencao.local' | transloco }}</mat-option>
              @for (m of maquinas(); track m.id) {
                <mat-option [value]="m.id">{{ m.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.manutencao.diretorio' | transloco }}</mat-label>
            <input matInput formControlName="dbDir" />
          </mat-form-field>
          <div class="acoes">
            <button mat-stroked-button type="button" (click)="executar('verify-archives')">
              {{ 'config.manutencao.verificar-agora' | transloco }}
            </button>
            <button mat-stroked-button type="button" (click)="executar('db-backup')">
              {{ 'config.manutencao.backup-agora' | transloco }}
            </button>
            <button mat-flat-button type="submit" [disabled]="form.invalid">
              {{ 'comum.salvar' | transloco }}
            </button>
          </div>
          @if (salvo()) {
            <p class="ok">
              <mat-icon aria-hidden="true">check_circle</mat-icon>
              {{ 'config.smtp.salvo' | transloco }}
            </p>
          }
        </form>
      </mat-card-content>
    </mat-card>

    <mat-card appearance="outlined">
      <mat-card-header
        ><mat-card-title>{{
          'config.reindexar.titulo' | transloco
        }}</mat-card-title></mat-card-header
      >
      <mat-card-content>
        <p>{{ 'config.reindexar.explicacao' | transloco }}</p>
        <form [formGroup]="reindex" (ngSubmit)="reindexar()" class="form">
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.reindexar.maquina' | transloco }}</mat-label>
            <mat-select formControlName="machineId">
              @for (m of todas(); track m.id) {
                <mat-option [value]="m.id">{{ m.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'config.reindexar.diretorio' | transloco }}</mat-label>
            <input matInput formControlName="dir" placeholder="/home/backup" />
          </mat-form-field>
          <div class="acoes">
            <button mat-flat-button type="submit" [disabled]="reindex.invalid">
              {{ 'config.reindexar.iniciar' | transloco }}
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    :host {
      display: contents;
    }
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
      margin-top: 8px;
    }
    .ok,
    .erro {
      display: flex;
      gap: 8px;
      align-items: center;
      margin: 0;
    }
    .ok {
      color: var(--m2m-ok);
    }
    .erro {
      color: var(--m2m-fail);
    }
  `,
})
export class MaintenanceSection implements OnInit {
  private http = inject(HttpClient);
  private fb = inject(FormBuilder);
  private machines = inject(MachinesService);
  private router = inject(Router);

  readonly form = this.fb.nonNullable.group({
    verifyEnabled: [true],
    verifyCron: ['0 0 4 * * SUN', Validators.required],
    dbEnabled: [true],
    dbTime: ['01:30', Validators.required],
    dbMachine: [0],
    dbDir: ['/home/backup/m2m-db', Validators.required],
  });
  readonly reindex = this.fb.nonNullable.group({
    machineId: [0, Validators.min(1)],
    dir: ['', Validators.required],
  });
  readonly todas = signal<Machine[]>([]);
  readonly maquinas = signal<Machine[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  readonly salvo = signal(false);

  async ngOnInit(): Promise<void> {
    const todas = await this.machines.list();
    this.todas.set(todas);
    this.maquinas.set(todas.filter((m) => m.protocol !== 'LOCAL'));
    const s = await firstValueFrom(this.http.get<MaintenanceSettings>('/api/settings/maintenance'));
    this.form.patchValue({
      verifyEnabled: s.verify.enabled,
      verifyCron: s.verify.cron,
      dbEnabled: s.dbBackup.enabled,
      dbTime: s.dbBackup.time,
      dbMachine: s.dbBackup.storageMachineId ?? 0,
      dbDir: s.dbBackup.storageDir,
    });
  }

  async salvar(): Promise<void> {
    this.erro.set(null);
    this.salvo.set(false);
    const v = this.form.getRawValue();
    const corpo: MaintenanceSettings = {
      verify: { enabled: v.verifyEnabled, cron: v.verifyCron.trim() },
      dbBackup: {
        enabled: v.dbEnabled,
        time: v.dbTime,
        storageMachineId: v.dbMachine || null,
        storageDir: v.dbDir.trim(),
      },
    };
    try {
      await firstValueFrom(this.http.put<MaintenanceSettings>('/api/settings/maintenance', corpo));
      this.salvo.set(true);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async executar(caminho: 'verify-archives' | 'db-backup'): Promise<void> {
    this.erro.set(null);
    try {
      const job = await firstValueFrom(
        this.http.post<JobView>(`/api/maintenance/${caminho}`, null),
      );
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async reindexar(): Promise<void> {
    this.erro.set(null);
    try {
      const job = await firstValueFrom(
        this.http.post<JobView>('/api/maintenance/reindex', this.reindex.getRawValue()),
      );
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
