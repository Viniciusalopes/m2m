import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine, MachinesService } from '../machines/machines.service';
import { ConfirmDialog } from '../ui/confirm-dialog';
import { DirBrowser, DirBrowserData } from '../ui/dir-browser';
import { ProfileRequest, ProfilesService } from './profiles.service';

interface DirEntry {
  path: string;
  /** machineId → caminho no destino. */
  mappings: Record<number, string>;
}

@Component({
  selector: 'm2m-profile-form',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatCheckboxModule,
    MatSlideToggleModule,
    TranslocoPipe,
  ],
  templateUrl: './profile-form.html',
  styleUrl: './profile-form.scss',
})
export class ProfileForm implements OnInit {
  private fb = inject(FormBuilder);
  private profiles = inject(ProfilesService);
  private machinesService = inject(MachinesService);
  private dialog = inject(MatDialog);
  readonly router = inject(Router);
  private route = inject(ActivatedRoute);

  readonly id = Number(this.route.snapshot.paramMap.get('id')) || null;
  readonly maquinas = signal<Machine[]>([]);
  readonly dirs = signal<DirEntry[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  readonly salvando = signal(false);

  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    complement: ['', Validators.pattern(/^[A-Za-z0-9_-]*$/)],
    sourceMachineId: [0, Validators.min(1)],
    storageMachineId: [0, Validators.min(1)],
    storagePath: ['', Validators.required],
    thresholdPercent: [50, [Validators.min(1), Validators.max(100)]],
    encryptionEnabled: [false],
    scheduleEnabled: [false],
    scheduleTime: ['02:00'],
  });

  async ngOnInit(): Promise<void> {
    this.maquinas.set(await this.machinesService.list());
    if (this.id) {
      const p = await this.profiles.get(this.id);
      this.form.patchValue({
        name: p.name,
        complement: p.complement ?? '',
        sourceMachineId: p.sourceMachineId,
        storageMachineId: p.storage.machineId,
        storagePath: p.storage.path,
        thresholdPercent: Math.round(p.suspectThreshold * 100),
        encryptionEnabled: p.encryptionEnabled,
        scheduleEnabled: p.backupSchedule?.enabled ?? false,
        scheduleTime: p.backupSchedule?.time ?? '02:00',
      });
      this.dirs.set(
        p.directories.map((d) => ({
          path: d.path,
          mappings: Object.fromEntries(d.mappings.map((m) => [m.machineId, m.path])),
        })),
      );
    } else {
      const d = await this.profiles.defaults();
      this.form.patchValue({
        storageMachineId: d.storageMachineId ?? this.maquinas()[0]?.id ?? 0,
        storagePath: d.storageDir,
        thresholdPercent: Math.round(d.suspectThreshold * 100),
        sourceMachineId:
          (this.maquinas().find((m) => m.protocol === 'LOCAL') ?? this.maquinas()[0])?.id ?? 0,
      });
    }
  }

  origem(): Machine | undefined {
    return this.maquinas().find((m) => m.id === this.form.controls.sourceMachineId.value);
  }

  armazenamento(): Machine | undefined {
    return this.maquinas().find((m) => m.id === this.form.controls.storageMachineId.value);
  }

  /** Máquinas que podem receber mapeamento: todas exceto a origem. */
  destinos(): Machine[] {
    return this.maquinas().filter((m) => m.id !== this.form.controls.sourceMachineId.value);
  }

  armazenamentoRemoto(): boolean {
    const m = this.armazenamento();
    return !!m && m.protocol !== 'LOCAL';
  }

  async adicionarDiretorio(): Promise<void> {
    const origem = this.origem();
    if (!origem) {
      return;
    }
    const data: DirBrowserData = { machineId: origem.id, machineName: origem.name };
    const path = await firstValueFrom(
      this.dialog.open(DirBrowser, { data, width: '40rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (path) {
      this.dirs.update((l) => [...l, { path, mappings: {} }]);
    }
  }

  removerDiretorio(i: number): void {
    this.dirs.update((l) => l.filter((_, k) => k !== i));
  }

  async escolherArmazenamento(): Promise<void> {
    const m = this.armazenamento();
    if (!m) {
      return;
    }
    const data: DirBrowserData = { machineId: m.id, machineName: m.name };
    const path = await firstValueFrom(
      this.dialog.open(DirBrowser, { data, width: '40rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (path) {
      this.form.controls.storagePath.setValue(path);
    }
  }

  async escolherMapeamento(i: number, machine: Machine): Promise<void> {
    const data: DirBrowserData = { machineId: machine.id, machineName: machine.name };
    const path = await firstValueFrom(
      this.dialog.open(DirBrowser, { data, width: '40rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (path) {
      this.definirMapeamento(i, machine.id, path);
    }
  }

  definirMapeamento(i: number, machineId: number, path: string): void {
    this.dirs.update((l) =>
      l.map((d, k) => (k === i ? { ...d, mappings: { ...d.mappings, [machineId]: path } } : d)),
    );
  }

  mapeamentoDe(i: number, machineId: number): string {
    return this.dirs()[i]?.mappings[machineId] ?? '';
  }

  /** Exporta a chave de criptografia do perfil: avisa antes, porque quem a tem decifra os backups. */
  async exportarChave(): Promise<void> {
    if (!this.id) {
      return;
    }
    const ok = await firstValueFrom(
      this.dialog
        .open(ConfirmDialog, {
          data: {
            titulo: 'perfis.chave.titulo',
            mensagem: 'perfis.chave.mensagem',
            confirmar: 'perfis.chave.confirmar',
          },
        })
        .afterClosed(),
    );
    if (!ok) {
      return;
    }
    try {
      const texto = await this.profiles.exportKey(this.id);
      const url = URL.createObjectURL(new Blob([texto], { type: 'application/json' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = 'chave-do-perfil.json';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async salvar(): Promise<void> {
    if (this.form.invalid || this.salvando()) {
      return;
    }
    this.salvando.set(true);
    this.erro.set(null);
    const v = this.form.getRawValue();
    const req: ProfileRequest = {
      name: v.name.trim(),
      complement: v.complement.trim() || null,
      sourceMachineId: v.sourceMachineId,
      directories: this.dirs().map((d) => ({
        path: d.path,
        mappings: Object.entries(d.mappings)
          .filter(([, path]) => path.trim() !== '')
          .map(([machineId, path]) => ({ machineId: Number(machineId), path: path.trim() })),
      })),
      storage: { machineId: v.storageMachineId, path: v.storagePath.trim() },
      suspectThreshold: v.thresholdPercent / 100,
      encryptionEnabled: v.encryptionEnabled,
      backupSchedule: { time: v.scheduleTime, enabled: v.scheduleEnabled },
    };
    try {
      if (this.id) {
        await this.profiles.update(this.id, req);
      } else {
        await this.profiles.create(req);
      }
      await this.router.navigate(['/perfis']);
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.salvando.set(false);
    }
  }
}
