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
import { Router } from '@angular/router';
import { SelectiveDialog } from '../restore/selective-dialog';
import { RestoreService } from '../restore/restore.service';
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
  private router = inject(Router);
  private restoreService = inject(RestoreService);

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

  async verConteudo(b: Backup): Promise<void> {
    const itens = await firstValueFrom(
      this.dialog.open(BackupTree, { data: b, width: '820px', maxWidth: '95vw' }).afterClosed(),
    );
    if (itens?.length) {
      const jobId = await firstValueFrom(
        this.dialog
          .open(SelectiveDialog, {
            data: { backup: b, items: itens },
            width: '760px',
            maxWidth: '95vw',
          })
          .afterClosed(),
      );
      if (jobId) {
        await this.router.navigate(['/jobs', jobId]);
      }
    }
  }

  restaurar(b: Backup): void {
    void this.router.navigate(['/restaurar', b.id]);
  }

  /** Desfazer: restaura o backup de segurança nas mesmas máquina e caminhos de onde foi gerado. */
  async desfazer(b: Backup): Promise<void> {
    const ok = await firstValueFrom(
      this.dialog
        .open(ConfirmDialog, {
          data: {
            titulo: 'restauracao.desfazer.titulo',
            mensagem: 'restauracao.desfazer.mensagem',
            confirmar: 'restauracao.desfazer.confirmar',
            params: { nome: b.fileName },
          },
        })
        .afterClosed(),
    );
    if (!ok) {
      return;
    }
    this.erro.set(null);
    try {
      const job = await this.restoreService.undo(b.id);
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
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
