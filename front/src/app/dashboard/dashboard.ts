import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { BytesPipe } from '../ui/bytes.pipe';
import { Estado, StatusChip } from '../ui/status-chip';
import { Dashboard, DashboardService, StorageUsage } from './dashboard.service';

const ESTADOS: Record<string, Estado> = {
  COMPLETED: 'ok',
  COMPLETED_WITH_WARNINGS: 'warn',
  SUSPECT: 'suspect',
  FAILED: 'fail',
  INTERRUPTED: 'warn',
  CORRUPTED: 'corrupt',
  MISSING: 'fail',
};

/** Painel inicial (D27): job atual e fila, perfis, agendamentos, alertas, atenção e espaço dos armazenamentos. */
@Component({
  selector: 'm2m-dashboard',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    StatusChip,
    TranslocoPipe,
    DatePipe,
    DecimalPipe,
    BytesPipe,
    RouterLink,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardPage implements OnInit, OnDestroy {
  private service = inject(DashboardService);
  readonly dados = signal<Dashboard | null>(null);
  private timer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    void this.atualizar();
    this.timer = setInterval(() => void this.atualizar(), 10_000);
  }

  ngOnDestroy(): void {
    if (this.timer) {
      clearInterval(this.timer);
    }
  }

  async atualizar(): Promise<void> {
    try {
      this.dados.set(await this.service.get());
    } catch {
      /* mantém o último painel; o interceptor trata a sessão expirada */
    }
  }

  estado(status: string): Estado {
    return ESTADOS[status] ?? 'info';
  }

  estadoEspaco(s: StorageUsage): Estado {
    return s.state === 'LOW' ? 'fail' : s.state === 'OK' ? 'ok' : 'info';
  }
}
