import { HttpClient, HttpParams } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { GrafanaConfig, grafanaUrl } from '../../core/grafana-url';
import { StatusChip } from '../../ui/status-chip';

interface Evento {
  id: number;
  occurredAt: string;
  username: string | null;
  ip: string | null;
  action: string;
  target: string | null;
  result: string;
  details: string;
}

interface Pagina {
  items: Evento[];
  total: number;
}

@Component({
  selector: 'm2m-audit',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    StatusChip,
    TranslocoPipe,
  ],
  templateUrl: './audit.html',
  styleUrl: './audit.scss',
})
export class Audit implements OnInit {
  private http = inject(HttpClient);
  private fb = inject(FormBuilder);

  readonly colunas = ['quando', 'usuario', 'ip', 'acao', 'alvo', 'resultado'];
  readonly filtros = this.fb.nonNullable.group({ from: '', to: '', user: '', action: '' });
  readonly itens = signal<Evento[]>([]);
  readonly total = signal(0);
  readonly pagina = signal(0);
  readonly tamanho = signal(50);
  readonly carregando = signal(false);
  /** Endereço do Grafana (null enquanto a configuração não chega ou se ela faltar: o botão fica oculto). */
  readonly grafana = signal<string | null>(null);

  ngOnInit(): void {
    void this.carregar();
    void this.carregarGrafana();
  }

  private async carregarGrafana(): Promise<void> {
    try {
      const c = await firstValueFrom(this.http.get<GrafanaConfig>('/config/grafana.json'));
      this.grafana.set(grafanaUrl(window.location.hostname, c));
    } catch {
      this.grafana.set(null);
    }
  }

  private params(): HttpParams {
    const f = this.filtros.getRawValue();
    let p = new HttpParams();
    if (f.from) p = p.set('from', new Date(f.from).toISOString());
    if (f.to) p = p.set('to', new Date(f.to).toISOString());
    if (f.user.trim()) p = p.set('user', f.user.trim());
    if (f.action.trim()) p = p.set('action', f.action.trim().toUpperCase());
    return p;
  }

  async carregar(): Promise<void> {
    this.carregando.set(true);
    try {
      const p = this.params().set('page', this.pagina()).set('size', this.tamanho());
      const r = await firstValueFrom(this.http.get<Pagina>('/api/audit', { params: p }));
      this.itens.set(r.items);
      this.total.set(r.total);
    } finally {
      this.carregando.set(false);
    }
  }

  filtrar(): void {
    this.pagina.set(0);
    void this.carregar();
  }

  mudarPagina(e: PageEvent): void {
    this.pagina.set(e.pageIndex);
    this.tamanho.set(e.pageSize);
    void this.carregar();
  }

  /** O CSV é baixado pelo navegador com a sessão atual. */
  exportar(): void {
    const qs = this.params().toString();
    window.location.href = '/api/audit/export' + (qs ? '?' + qs : '');
  }
}
