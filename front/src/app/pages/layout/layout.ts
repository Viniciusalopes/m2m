import { HttpClient } from '@angular/common/http';
import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ThemeService } from '../../core/theme.service';
import { NotificationsService } from '../../notifications/notifications.service';

interface ItemMenu {
  rota: string;
  icone: string;
  chave: string;
}

@Component({
  selector: 'm2m-layout',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatBadgeModule,
    TranslocoPipe,
  ],
  templateUrl: './layout.html',
  styleUrl: './layout.scss',
})
export class Layout implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private router = inject(Router);
  readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  private notificacoes = inject(NotificationsService);

  readonly menuAberto = signal(true);
  readonly chaveMestraConfirmada = signal(true);
  /** Contador do sino, atualizado em tempo real. */
  readonly naoLidas = this.notificacoes.unread;

  readonly itens: ItemMenu[] = [
    { rota: '/', icone: 'dashboard', chave: 'menu.painel' },
    { rota: '/maquinas', icone: 'computer', chave: 'menu.maquinas' },
    { rota: '/perfis', icone: 'folder_copy', chave: 'menu.perfis' },
    { rota: '/backups', icone: 'inventory_2', chave: 'menu.backups' },
    { rota: '/jobs', icone: 'monitoring', chave: 'menu.acompanhamento' },
    { rota: '/notificacoes', icone: 'notifications', chave: 'menu.notificacoes' },
    { rota: '/configuracoes', icone: 'settings', chave: 'menu.configuracoes' },
    { rota: '/auditoria', icone: 'fact_check', chave: 'menu.auditoria' },
    { rota: '/sobre', icone: 'info', chave: 'menu.sobre' },
  ];

  async ngOnInit(): Promise<void> {
    this.notificacoes.connect();
    try {
      const s = await firstValueFrom(
        this.http.get<{ acknowledged: boolean }>('/api/system/master-key'),
      );
      this.chaveMestraConfirmada.set(s.acknowledged);
    } catch {
      /* o aviso é reexibido na próxima navegação */
    }
  }

  ngOnDestroy(): void {
    this.notificacoes.disconnect();
  }

  async confirmarChaveMestra(): Promise<void> {
    await firstValueFrom(this.http.post('/api/system/master-key/acknowledge', null));
    this.chaveMestraConfirmada.set(true);
  }

  async sair(): Promise<void> {
    await this.auth.logout();
    await this.router.navigate(['/login']);
  }
}
