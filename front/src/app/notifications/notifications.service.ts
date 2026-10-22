import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface AppNotification {
  id: number;
  type: string;
  severity: 'INFO' | 'WARN' | 'ERROR';
  messageCode: string;
  params: Record<string, unknown>;
  targetType: string | null;
  targetId: number | null;
  read: boolean;
  emailStatus: string | null;
  emailError: string | null;
  createdAt: string;
}

export interface NotificationPage {
  items: AppNotification[];
  unread: number;
}

/** Notificações do painel: lista, contador do sino e atualização em tempo real (SSE). */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private http = inject(HttpClient);
  readonly unread = signal(0);
  private source: EventSource | null = null;

  list(unreadOnly = false, page = 0, size = 50): Promise<NotificationPage> {
    const params = new HttpParams()
      .set('unreadOnly', unreadOnly)
      .set('page', page)
      .set('size', size);
    return firstValueFrom(this.http.get<NotificationPage>('/api/notifications', { params }));
  }

  async markRead(id: number): Promise<void> {
    await firstValueFrom(this.http.post<void>(`/api/notifications/${id}/read`, null));
    await this.refreshCount();
  }

  async markAllRead(): Promise<void> {
    await firstValueFrom(this.http.post<void>('/api/notifications/read-all', null));
    this.unread.set(0);
  }

  async refreshCount(): Promise<void> {
    const r = await firstValueFrom(
      this.http.get<{ unread: number }>('/api/notifications/unread-count'),
    );
    this.unread.set(r.unread);
  }

  /** Abre o fluxo em tempo real; `onNew` é chamado a cada notificação recebida. */
  connect(onNew?: () => void): void {
    this.disconnect();
    void this.refreshCount();
    this.source = new EventSource('/api/notifications/events');
    this.source.addEventListener('progress', () => {
      void this.refreshCount();
      onNew?.();
    });
  }

  disconnect(): void {
    this.source?.close();
    this.source = null;
  }
}
