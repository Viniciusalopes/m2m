import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatStepperModule } from '@angular/material/stepper';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Backup, BackupsService } from '../backups/backups.service';
import { Machine, MachinesService } from '../machines/machines.service';
import { DirBrowser } from '../ui/dir-browser';
import { DefaultTarget, Preview, RestoreService } from './restore.service';

/** Assistente de restauração em espelho: backup → destino → caminhos editáveis → prévia → confirmação. */
@Component({
  selector: 'm2m-restore-wizard',
  imports: [
    FormsModule,
    MatStepperModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    TranslocoPipe,
    DecimalPipe,
  ],
  templateUrl: './restore-wizard.html',
  styleUrl: './restore-wizard.scss',
})
export class RestoreWizard implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private backups = inject(BackupsService);
  private machinesService = inject(MachinesService);
  private service = inject(RestoreService);
  private dialog = inject(MatDialog);

  readonly backup = signal<Backup | null>(null);
  readonly maquinas = signal<Machine[]>([]);
  readonly machineId = signal<number | null>(null);
  readonly caminhos = signal<DefaultTarget[]>([]);
  readonly preview = signal<Preview | null>(null);
  readonly confirmado = signal(false);
  readonly erro = signal<ApiErro | null>(null);
  readonly carregando = signal(false);

  readonly destino = computed(() => this.maquinas().find((m) => m.id === this.machineId()) ?? null);
  readonly caminhosCompletos = computed(
    () => this.caminhos().length > 0 && this.caminhos().every((c) => !!c.targetPath?.trim()),
  );
  private id = 0;

  async ngOnInit(): Promise<void> {
    this.id = Number(this.route.snapshot.paramMap.get('id'));
    const [lista, maquinas] = await Promise.all([
      this.backups.list({}, 0, 200),
      this.machinesService.list(),
    ]);
    this.backup.set(lista.find((b) => b.id === this.id) ?? null);
    // só máquinas com permissão de escrita podem ser destino
    this.maquinas.set(maquinas.filter((m) => m.capabilities.writable));
  }

  async escolherDestino(id: number): Promise<void> {
    this.machineId.set(id);
    this.preview.set(null);
    this.confirmado.set(false);
    this.erro.set(null);
    try {
      this.caminhos.set(await this.service.defaults(this.id, id));
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  editar(dir: number, valor: string): void {
    this.caminhos.update((l) => l.map((c) => (c.dir === dir ? { ...c, targetPath: valor } : c)));
    this.preview.set(null);
    this.confirmado.set(false);
  }

  async navegar(dir: number): Promise<void> {
    const m = this.destino();
    if (!m) {
      return;
    }
    const path = await firstValueFrom(
      this.dialog
        .open(DirBrowser, {
          data: { machineId: m.id, machineName: m.name },
          width: '40rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (path) {
      this.editar(dir, path);
    }
  }

  async gerarPrevia(): Promise<void> {
    const m = this.destino();
    if (!m) {
      return;
    }
    this.carregando.set(true);
    this.erro.set(null);
    try {
      this.preview.set(
        await this.service.preview(
          this.id,
          m.id,
          this.caminhos().map((c) => ({ dir: c.dir, path: c.targetPath!.trim() })),
        ),
      );
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.carregando.set(false);
    }
  }

  async iniciar(): Promise<void> {
    const m = this.destino();
    if (!m || !this.confirmado()) {
      return;
    }
    this.erro.set(null);
    try {
      const job = await this.service.start(
        this.id,
        m.id,
        this.caminhos().map((c) => ({ dir: c.dir, path: c.targetPath!.trim() })),
      );
      await this.router.navigate(['/jobs', job.id]);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  cancelar(): void {
    void this.router.navigate(['/backups']);
  }
}
