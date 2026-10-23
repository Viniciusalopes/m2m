import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MatDialog,
  MatDialogModule,
  MatDialogRef,
  MAT_DIALOG_DATA,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Backup } from '../backups/backups.service';
import { Machine, MachinesService } from '../machines/machines.service';
import { DirBrowser } from '../ui/dir-browser';
import { Conflict, ConflictAction, RestoreService } from './restore.service';

export interface SelectiveData {
  backup: Backup;
  items: string[];
}

export const ACOES: ConflictAction[] = [
  'OVERWRITE',
  'OVERWRITE_NEWER',
  'OVERWRITE_SIZE',
  'OVERWRITE_SIZE_OR_NEWER',
  'RENAME',
  'SKIP',
];

/** Restauração seletiva: destino qualquer, conflitos com as ações do FileZilla e "aplicar a todos". */
@Component({
  selector: 'm2m-selective-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    TranslocoPipe,
  ],
  templateUrl: './selective-dialog.html',
  styleUrl: './selective-dialog.scss',
})
export class SelectiveDialog implements OnInit {
  private service = inject(RestoreService);
  private machinesService = inject(MachinesService);
  private dialog = inject(MatDialog);
  private ref = inject(MatDialogRef<SelectiveDialog, number>);
  readonly data = inject<SelectiveData>(MAT_DIALOG_DATA);

  readonly acoes = ACOES;
  readonly maquinas = signal<Machine[]>([]);
  readonly machineId = signal<number | null>(null);
  readonly targetDir = signal('');
  readonly conflitos = signal<Conflict[] | null>(null);
  readonly decisoes = signal<Record<string, ConflictAction>>({});
  readonly padrao = signal<ConflictAction>('SKIP');
  readonly erro = signal<ApiErro | null>(null);
  readonly ocupado = signal(false);

  readonly destino = computed(() => this.maquinas().find((m) => m.id === this.machineId()) ?? null);

  async ngOnInit(): Promise<void> {
    this.maquinas.set((await this.machinesService.list()).filter((m) => m.capabilities.writable));
  }

  async navegar(): Promise<void> {
    const m = this.destino();
    if (!m) {
      return;
    }
    const p = await firstValueFrom(
      this.dialog
        .open(DirBrowser, {
          data: { machineId: m.id, machineName: m.name },
          width: '40rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (p) {
      this.targetDir.set(p);
      this.conflitos.set(null);
    }
  }

  podeVerificar(): boolean {
    return !!this.machineId() && this.targetDir().trim().startsWith('/');
  }

  async verificar(): Promise<void> {
    const m = this.destino();
    if (!m) {
      return;
    }
    this.erro.set(null);
    this.ocupado.set(true);
    try {
      this.conflitos.set(
        await this.service.plan(this.data.backup.id, {
          machineId: m.id,
          targetDir: this.targetDir().trim(),
          items: this.data.items,
        }),
      );
      this.decisoes.set({});
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.ocupado.set(false);
    }
  }

  decisao(c: Conflict): ConflictAction {
    return this.decisoes()[c.entry] ?? this.padrao();
  }

  definir(c: Conflict, acao: ConflictAction): void {
    this.decisoes.update((d) => ({ ...d, [c.entry]: acao }));
  }

  /** "Aplicar a todos": uma só ação para todos os conflitos. */
  aplicarATodos(acao: ConflictAction): void {
    this.padrao.set(acao);
    this.decisoes.set({});
  }

  async iniciar(): Promise<void> {
    const m = this.destino();
    if (!m) {
      return;
    }
    this.erro.set(null);
    try {
      const job = await this.service.startSelective(this.data.backup.id, {
        machineId: m.id,
        targetDir: this.targetDir().trim(),
        items: this.data.items,
        defaultAction: this.padrao(),
        decisions: this.decisoes(),
      });
      this.ref.close(job.id);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
