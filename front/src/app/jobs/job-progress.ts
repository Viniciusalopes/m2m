import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { JobView } from '../profiles/profiles.service';
import { Estado, StatusChip } from '../ui/status-chip';
import { VerifyReport } from './verify-report';
import { JobEvent, JobMessage, JobsService } from './jobs.service';

const ESTADOS: Record<string, Estado> = {
  QUEUED: 'info',
  RUNNING: 'running',
  COMPLETED: 'ok',
  COMPLETED_WITH_WARNINGS: 'warn',
  FAILED: 'fail',
  INTERRUPTED: 'warn',
  CANCELED: 'info',
  AWAITING_CONFIRMATION: 'warn',
};

/** Acompanhamento do job: barra, arquivo atual, contagens, etapa e botão de emergência (D10). */
@Component({
  selector: 'm2m-job-progress',
  imports: [
    MatCardModule,
    MatProgressBarModule,
    MatButtonModule,
    MatIconModule,
    StatusChip,
    TranslocoPipe,
    DecimalPipe,
    DatePipe,
    RouterLink,
    VerifyReport,
  ],
  templateUrl: './job-progress.html',
  styleUrl: './job-progress.scss',
})
export class JobProgressPage implements OnInit, OnDestroy {
  private jobs = inject(JobsService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  readonly job = signal<JobView | null>(null);
  readonly evento = signal<JobEvent | null>(null);
  readonly mensagens = signal<JobMessage[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  readonly cancelando = signal(false);
  private fechar: (() => void) | null = null;

  readonly status = computed(() => this.evento()?.status ?? this.job()?.status ?? 'QUEUED');
  readonly estado = computed<Estado>(() => ESTADOS[this.status()] ?? 'info');
  readonly ativo = computed(() => this.status() === 'RUNNING' || this.status() === 'QUEUED');
  readonly percent = computed(() => this.evento()?.percent ?? this.job()?.progress?.percent ?? 0);
  readonly dados = computed(
    () => ({ ...(this.job()?.progress ?? {}), ...(this.evento() ?? {}) }) as Partial<JobEvent>,
  );
  readonly resultado = computed(() => this.evento()?.result ?? this.job()?.result ?? null);
  readonly erroDoJob = computed(() => {
    const e = (this.resultado() as { error?: ApiErro } | null)?.error;
    return e ?? null;
  });
  readonly interrompidoPeloUsuario = computed(() => this.status() === 'INTERRUPTED');

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    try {
      this.job.set(await this.jobs.get(id));
      this.fechar = this.jobs.watch(id, (e) => {
        this.evento.set(e);
        if (e.type === 'done') {
          void this.recarregar(id);
        }
      });
      if (!this.ativo()) {
        await this.recarregar(id);
      }
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  private async recarregar(id: number): Promise<void> {
    this.job.set(await this.jobs.get(id));
    this.mensagens.set(await this.jobs.messages(id));
  }

  ngOnDestroy(): void {
    this.fechar?.();
  }

  async emergencia(): Promise<void> {
    const j = this.job();
    if (!j || this.cancelando()) {
      return;
    }
    this.cancelando.set(true);
    try {
      await this.jobs.cancel(j.id);
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.cancelando.set(false);
    }
  }

  voltar(): void {
    void this.router.navigate(['/perfis']);
  }
}
