import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { ConfirmDialog } from '../ui/confirm-dialog';
import { StatusChip } from '../ui/status-chip';
import { MachineForm, MachineFormData } from './machine-form';
import { MachineTest } from './machine-test';
import { Machine, MachinesService } from './machines.service';

@Component({
  selector: 'm2m-machines',
  imports: [
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    DatePipe,
    StatusChip,
    TranslocoPipe,
  ],
  templateUrl: './machines.html',
  styleUrl: './machines.scss',
})
export class Machines implements OnInit {
  private service = inject(MachinesService);
  private dialog = inject(MatDialog);

  readonly colunas = ['nome', 'protocolo', 'endereco', 'usuario', 'identidade', 'teste', 'acoes'];
  readonly maquinas = signal<Machine[]>([]);
  readonly erro = signal<(ApiErro & { usos?: string[] }) | null>(null);
  readonly carregando = signal(false);

  ngOnInit(): void {
    void this.carregar();
  }

  async carregar(): Promise<void> {
    this.carregando.set(true);
    try {
      this.maquinas.set(await this.service.list());
    } finally {
      this.carregando.set(false);
    }
  }

  private temLocal(): boolean {
    return this.maquinas().some((m) => m.protocol === 'LOCAL');
  }

  nova(): void {
    this.abrirFormulario(null);
  }

  editar(m: Machine): void {
    this.abrirFormulario(m);
  }

  private abrirFormulario(machine: Machine | null): void {
    const data: MachineFormData = { machine, localDisponivel: !this.temLocal() };
    this.dialog
      .open(MachineForm, { data, width: '40rem', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((salva?: Machine) => {
        if (salva) {
          void this.carregar();
        }
      });
  }

  testar(m: Machine): void {
    this.dialog
      .open(MachineTest, { data: m, width: '40rem', maxWidth: '95vw' })
      .afterClosed()
      .subscribe(() => void this.carregar());
  }

  async remover(m: Machine): Promise<void> {
    this.erro.set(null);
    // os backups ligados à máquina nunca são apagados: o aviso diz quantos são e que ficam no disco
    const backups = await this.service.archiveCounts(m.id).catch(() => null);
    const comBackups = !!backups && backups.fromMachine + backups.storedOnMachine > 0;
    const ok = await firstValueFrom(
      this.dialog
        .open(ConfirmDialog, {
          data: {
            titulo: 'maquinas.remover.titulo',
            mensagem: comBackups
              ? 'maquinas.remover.mensagem-backups'
              : 'maquinas.remover.mensagem',
            confirmar: 'comum.excluir',
            params: {
              nome: m.name,
              origem: backups?.fromMachine ?? 0,
              guardados: backups?.storedOnMachine ?? 0,
            },
            perigo: true,
          },
        })
        .afterClosed(),
    );
    if (!ok) {
      return;
    }
    try {
      await this.service.remove(m.id);
      await this.carregar();
    } catch (e) {
      const err = apiErro(e);
      this.erro.set({ ...err, usos: (err.parametros?.['usos'] as string[] | undefined) ?? [] });
    }
  }

  enderecoDe(m: Machine): string {
    return m.protocol === 'LOCAL' ? m.host : `${m.host}:${m.port}`;
  }
}
