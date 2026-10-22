import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Profile, ProfilesService } from '../profiles/profiles.service';
import { BytesPipe } from '../ui/bytes.pipe';
import { ConfirmDialog } from '../ui/confirm-dialog';
import { Estado, StatusChip } from '../ui/status-chip';
import { BackupTree } from './backup-tree';
import { Backup, BackupsService } from './backups.service';

const ESTADOS: Record<string, Estado> = {
  COMPLETED: 'ok',
  COMPLETED_WITH_WARNINGS: 'warn',
  SUSPECT: 'suspect',
  FAILED: 'fail',
  INTERRUPTED: 'warn',
  CORRUPTED: 'corrupt',
  MISSING: 'fail',
};

@Component({
  selector: 'm2m-backups',
  imports: [
    ReactiveFormsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatSelectModule,
    StatusChip,
    TranslocoPipe,
    DatePipe,
    BytesPipe,
  ],
  templateUrl: './backups.html',
  styleUrl: './backups.scss',
})
export class Backups implements OnInit {
  private service = inject(BackupsService);
  private profilesService = inject(ProfilesService);
  private dialog = inject(MatDialog);
  private fb = inject(FormBuilder);

  readonly colunas = [
    'data',
    'perfil',
    'tipo',
    'estado',
    'arquivos',
    'tamanho',
    'armazenamento',
    'acoes',
  ];
  readonly backups = signal<Backup[]>([]);
  readonly perfis = signal<Profile[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  readonly statuses = [
    'COMPLETED',
    'COMPLETED_WITH_WARNINGS',
    'SUSPECT',
    'FAILED',
    'INTERRUPTED',
    'CORRUPTED',
    'MISSING',
  ];

  readonly filtros = this.fb.group({
    profileId: [null as number | null],
    status: [''],
    type: [''],
    orphan: [false],
  });

  async ngOnInit(): Promise<void> {
    this.perfis.set(await this.profilesService.list());
    await this.carregar();
    this.filtros.valueChanges.subscribe(() => void this.carregar());
  }

  async carregar(): Promise<void> {
    const f = this.filtros.getRawValue();
    this.backups.set(await this.service.list({ ...f, orphan: f.orphan }));
  }

  estado(b: Backup): Estado {
    return ESTADOS[b.status] ?? 'info';
  }

  podeUsarArquivo(b: Backup): boolean {
    return (
      b.status === 'COMPLETED' || b.status === 'COMPLETED_WITH_WARNINGS' || b.status === 'SUSPECT'
    );
  }

  baixar(b: Backup): string {
    return this.service.downloadUrl(b.id);
  }

  verConteudo(b: Backup): void {
    this.dialog.open(BackupTree, { data: b, width: '820px', maxWidth: '95vw' });
  }

  async fixar(b: Backup): Promise<void> {
    await this.executar(() => this.service.pin(b.id, !b.pinned));
  }

  async aceitar(b: Backup): Promise<void> {
    await this.executar(() => this.service.accept(b.id));
  }

  async excluir(b: Backup): Promise<void> {
    const ok = await firstValueFrom(
      this.dialog
        .open(ConfirmDialog, {
          data: {
            titulo: 'backups.excluir.titulo',
            mensagem: b.pinned ? 'backups.excluir.mensagem-fixado' : 'backups.excluir.mensagem',
            confirmar: 'comum.excluir',
            params: { nome: b.fileName },
            perigo: true,
          },
        })
        .afterClosed(),
    );
    if (ok) {
      await this.executar(() => this.service.remove(b.id));
    }
  }

  private async executar(acao: () => Promise<unknown>): Promise<void> {
    this.erro.set(null);
    try {
      await acao();
      await this.carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
