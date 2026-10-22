import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { Estado, StatusChip } from '../ui/status-chip';
import { AppNotification, NotificationsService } from './notifications.service';

/** Central de notificações: lista completa, com marcação de lida e o resultado do envio de e-mail. */
@Component({
  selector: 'm2m-notifications',
  imports: [MatButtonModule, MatIconModule, StatusChip, TranslocoPipe, DatePipe, RouterLink],
  templateUrl: './notifications.html',
  styleUrl: './notifications.scss',
})
export class Notifications implements OnInit {
  private service = inject(NotificationsService);
  readonly itens = signal<AppNotification[]>([]);
  readonly apenasNaoLidas = signal(false);

  ngOnInit(): void {
    void this.carregar();
  }

  async carregar(): Promise<void> {
    this.itens.set((await this.service.list(this.apenasNaoLidas())).items);
  }

  alternarFiltro(): void {
    this.apenasNaoLidas.update((v) => !v);
    void this.carregar();
  }

  estado(n: AppNotification): Estado {
    return n.severity === 'ERROR' ? 'fail' : n.severity === 'WARN' ? 'warn' : 'info';
  }

  async lida(n: AppNotification): Promise<void> {
    await this.service.markRead(n.id);
    await this.carregar();
  }

  async todasLidas(): Promise<void> {
    await this.service.markAllRead();
    await this.carregar();
  }

  /** Link do alvo da notificação, quando existe. */
  alvo(n: AppNotification): string[] | null {
    if (n.targetType === 'job' && n.targetId) {
      return ['/jobs', String(n.targetId)];
    }
    if (n.targetType === 'backup') {
      return ['/backups'];
    }
    return null;
  }
}
