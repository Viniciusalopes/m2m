import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { StatusChip } from '../ui/status-chip';
import { BackupDialog } from './backup-dialog';
import { DeleteProfileDialog } from './delete-profile-dialog';
import { SyncDialog } from './sync-dialog';
import { SyncSchedulesDialog } from './sync-schedules-dialog';
import { VerifyDialog } from './verify-dialog';
import { SyncService } from '../sync/sync.service';
import { Profile, ProfilesService } from './profiles.service';

@Component({
  selector: 'm2m-profiles',
  imports: [
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    RouterLink,
    StatusChip,
    TranslocoPipe,
  ],
  templateUrl: './profiles.html',
  styleUrl: './profiles.scss',
})
export class Profiles implements OnInit {
  private service = inject(ProfilesService);
  private dialog = inject(MatDialog);
  private router = inject(Router);
  private syncService = inject(SyncService);

  readonly colunas = ['nome', 'origem', 'diretorios', 'armazenamento', 'agendamento', 'acoes'];
  readonly perfis = signal<Profile[]>([]);
  readonly erro = signal<(ApiErro & { jobId?: number }) | null>(null);

  ngOnInit(): void {
    void this.carregar();
  }

  async carregar(): Promise<void> {
    this.perfis.set(await this.service.list());
  }

  async backup(p: Profile): Promise<void> {
    this.erro.set(null);
    const r = await firstValueFrom(
      this.dialog.open(BackupDialog, { data: p, width: '30rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (r === undefined) {
      return;
    }
    try {
      const job = await this.service.startBackup(p.id, r.complement);
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      const err = apiErro(e);
      this.erro.set({ ...err, jobId: err.parametros?.['jobId'] as number | undefined });
    }
  }

  async verificar(p: Profile): Promise<void> {
    this.erro.set(null);
    const r = await firstValueFrom(
      this.dialog.open(VerifyDialog, { data: p, width: '32.5rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (!r) {
      return;
    }
    try {
      const job = await this.service.verify(p.id, r.destMachineId, r.mode);
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      const err = apiErro(e);
      this.erro.set({ ...err, jobId: err.parametros?.['jobId'] as number | undefined });
    }
  }

  async sincronizar(p: Profile): Promise<void> {
    this.erro.set(null);
    const r = await firstValueFrom(
      this.dialog.open(SyncDialog, { data: p, width: '38.75rem', maxWidth: '95vw' }).afterClosed(),
    );
    if (!r) {
      return;
    }
    try {
      const job = await this.syncService.start(p.id, r);
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      const err = apiErro(e);
      this.erro.set({ ...err, jobId: err.parametros?.['jobId'] as number | undefined });
    }
  }

  agendamentosSync(p: Profile): void {
    this.dialog.open(SyncSchedulesDialog, { data: p, width: '38.75rem', maxWidth: '95vw' });
  }

  async excluir(p: Profile): Promise<void> {
    this.erro.set(null);
    const keep = await firstValueFrom(
      this.dialog
        .open(DeleteProfileDialog, { data: p, width: '32.5rem', maxWidth: '95vw' })
        .afterClosed(),
    );
    if (keep === undefined) {
      return;
    }
    try {
      await this.service.remove(p.id, keep);
      await this.carregar();
    } catch (e) {
      const err = apiErro(e);
      this.erro.set({ ...err, jobId: err.parametros?.['jobId'] as number | undefined });
    }
  }
}
