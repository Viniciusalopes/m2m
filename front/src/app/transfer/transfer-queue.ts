import { DecimalPipe } from '@angular/common';
import { Component, inject, OnDestroy, OnInit, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { BytesPipe } from '../ui/bytes.pipe';
import { Estado, StatusChip } from '../ui/status-chip';
import { TransferEvent, TransferService, TransferView } from './transfer.service';

const ESTADOS: Record<string, Estado> = {
  QUEUED: 'info',
  TRANSFERRING: 'running',
  COMPLETED: 'ok',
  FAILED: 'fail',
  CANCELED: 'warn',
  SKIPPED: 'info',
  INTERRUPTED: 'warn',
};

/** Fila de transferências (D22): status, porcentagem, velocidade, cancelar, retomar, parar tudo e repetir falhas. */
@Component({
  selector: 'm2m-transfer-queue',
  imports: [
    DecimalPipe,
    BytesPipe,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    StatusChip,
    TranslocoPipe,
  ],
  template: `
    <section class="fila" [attr.aria-label]="'transferencia.fila.titulo' | transloco">
      <header class="cabecalho">
        <h2>{{ 'transferencia.fila.titulo' | transloco }}</h2>
        <div class="acoes">
          <button mat-stroked-button type="button" (click)="pararTudo()" [disabled]="!temAtivos()">
            <mat-icon>stop_circle</mat-icon>{{ 'transferencia.fila.parar-tudo' | transloco }}
          </button>
          <button mat-stroked-button type="button" (click)="repetirFalhas()">
            <mat-icon>replay</mat-icon>{{ 'transferencia.fila.repetir-falhas' | transloco }}
          </button>
          <button mat-stroked-button type="button" (click)="retomarInterrompidos()">
            <mat-icon>play_arrow</mat-icon>{{ 'transferencia.fila.retomar' | transloco }}
          </button>
        </div>
      </header>
      @if (erro(); as e) {
        <p class="erro" role="alert">
          <mat-icon aria-hidden="true">error</mat-icon
          >{{ 'erro.' + e.codigo | transloco: e.parametros }}
        </p>
      }
      <div class="rolagem">
        <table class="tabela">
          <thead>
            <tr>
              <th>{{ 'transferencia.fila.origem' | transloco }}</th>
              <th>{{ 'transferencia.fila.destino' | transloco }}</th>
              <th class="num">{{ 'transferencia.col.tamanho' | transloco }}</th>
              <th>{{ 'transferencia.fila.status' | transloco }}</th>
              <th class="num">%</th>
              <th class="num">{{ 'transferencia.fila.velocidade' | transloco }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (i of itens(); track i.id) {
              <tr>
                <td class="caminho" [title]="i.srcPath ?? ''">
                  {{ i.srcMachine }}:{{ i.srcPath }}
                </td>
                <td class="caminho" [title]="i.dstPath">{{ i.dstMachine }}:{{ i.dstPath }}</td>
                <td class="num">{{ i.kind === 'FILE' ? (i.size | bytes) : '' }}</td>
                <td>
                  <m2m-status
                    [estado]="estado(i.status)"
                    [texto]="'transferencia.status.' + i.status | transloco"
                  />
                  @if (motivo(i); as m) {
                    <div class="motivo">{{ m.chave | transloco: m.params }}</div>
                  }
                </td>
                <td class="num">
                  @if (i.kind === 'FILE') {
                    <mat-progress-bar
                      mode="determinate"
                      [value]="i.percent"
                      [attr.aria-label]="'jobs.progresso' | transloco"
                    />
                    {{ i.percent | number: '1.0-0' : 'pt-BR' }}%
                  }
                </td>
                <td class="num">{{ velocidade(i) }}</td>
                <td class="num">
                  @if (i.status === 'QUEUED' || i.status === 'TRANSFERRING') {
                    <button
                      mat-icon-button
                      type="button"
                      (click)="cancelar(i)"
                      [attr.aria-label]="'transferencia.fila.cancelar' | transloco"
                    >
                      <mat-icon>cancel</mat-icon>
                    </button>
                  }
                  @if (i.resumable) {
                    <button
                      mat-icon-button
                      type="button"
                      (click)="retomar(i)"
                      [attr.aria-label]="'transferencia.fila.retomar-item' | transloco"
                    >
                      <mat-icon>play_arrow</mat-icon>
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="7" class="vazio">{{ 'transferencia.fila.vazia' | transloco }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
  styles: `
    .fila {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
      background: var(--mat-sys-surface);
      padding: 8px 12px;
    }
    .cabecalho {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 8px;
    }
    .cabecalho h2 {
      margin: 0;
      font: var(--mat-sys-title-medium);
    }
    .acoes {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .rolagem {
      max-height: 260px;
      overflow: auto;
    }
    .tabela {
      width: 100%;
      border-collapse: collapse;
    }
    .tabela th {
      position: sticky;
      top: 0;
      background: var(--mat-sys-surface-container);
      text-align: left;
      padding: 4px 8px;
      font: var(--mat-sys-label-large);
    }
    .tabela td {
      padding: 4px 8px;
    }
    .caminho {
      max-width: 16.25rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .num {
      text-align: right;
      white-space: nowrap;
    }
    .motivo {
      font-size: 0.8em;
      color: var(--mat-sys-on-surface-variant);
    }
    .vazio {
      text-align: center;
      padding: 16px;
      color: var(--mat-sys-on-surface-variant);
    }
    .erro {
      color: var(--m2m-fail);
      display: flex;
      gap: 6px;
      align-items: center;
    }
  `,
})
export class TransferQueue implements OnInit, OnDestroy {
  private service = inject(TransferService);

  /** Algum item mudou de estado (para o pai atualizar os painéis ao terminar). */
  readonly terminou = output<void>();

  readonly itens = signal<TransferView[]>([]);
  readonly erro = signal<ApiErro | null>(null);
  private velocidades = new Map<number, number>();
  private fechar: (() => void) | null = null;
  private recarga: ReturnType<typeof setTimeout> | null = null;

  async ngOnInit(): Promise<void> {
    await this.carregar();
    this.fechar = this.service.watch((e) => this.aoEvento(e));
  }

  ngOnDestroy(): void {
    this.fechar?.();
    if (this.recarga) {
      clearTimeout(this.recarga);
    }
  }

  private carregado = false;

  async carregar(): Promise<void> {
    const antes = new Map(this.itens().map((i) => [i.id, i.status]));
    const novos = await this.service.queue();
    this.itens.set(novos);
    // item que apareceu já terminado (ou terminou) desde a última carga: avisa o pai para atualizar os painéis
    const final = (st: string | undefined): boolean =>
      !!st && !['QUEUED', 'TRANSFERRING'].includes(st);
    if (this.carregado && novos.some((i) => final(i.status) && !final(antes.get(i.id)))) {
      this.terminou.emit();
    }
    this.carregado = true;
  }

  temAtivos(): boolean {
    return this.itens().some((i) => i.status === 'QUEUED' || i.status === 'TRANSFERRING');
  }

  private aoEvento(e: TransferEvent): void {
    this.velocidades.set(e.id, e.speed);
    const lista = this.itens();
    const existente = lista.find((i) => i.id === e.id);
    if (!existente) {
      this.agendarRecarga();
      return;
    }
    const anterior = existente.status;
    this.itens.set(
      lista.map((i) =>
        i.id === e.id
          ? {
              ...i,
              status: e.status,
              bytesDone: e.bytesDone,
              percent: e.size > 0 ? Math.min(100, (e.bytesDone * 100) / e.size) : i.percent,
              resumable: ['FAILED', 'CANCELED', 'INTERRUPTED'].includes(e.status),
            }
          : i,
      ),
    );
    if (anterior !== e.status && !['QUEUED', 'TRANSFERRING'].includes(e.status)) {
      // o motivo (erro, nota, pulado) só vem do servidor: recarrega logo depois do fim do item
      this.agendarRecarga();
      this.terminou.emit();
    }
  }

  private agendarRecarga(): void {
    if (this.recarga) {
      return;
    }
    this.recarga = setTimeout(() => {
      this.recarga = null;
      void this.carregar();
    }, 400);
  }

  estado(status: string): Estado {
    return ESTADOS[status] ?? 'info';
  }

  velocidade(i: TransferView): string {
    const v = this.velocidades.get(i.id);
    return i.status === 'TRANSFERRING' && v
      ? `${(v / 1048576).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MiB/s`
      : '';
  }

  motivo(i: TransferView): { chave: string; params?: Record<string, unknown> } | null {
    if (i.errorCode) {
      return { chave: 'erro.' + i.errorCode, params: i.errorParams ?? undefined };
    }
    if (i.skipReason) {
      return { chave: 'transferencia.motivo.' + i.skipReason };
    }
    if (i.note) {
      return { chave: 'transferencia.nota.' + i.note };
    }
    return null;
  }

  private async acao(f: () => Promise<unknown>): Promise<void> {
    this.erro.set(null);
    try {
      await f();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
    await this.carregar();
  }

  cancelar(i: TransferView): Promise<void> {
    return this.acao(() => this.service.cancel(i.id));
  }

  retomar(i: TransferView): Promise<void> {
    return this.acao(() => this.service.resume(i.id));
  }

  pararTudo(): Promise<void> {
    return this.acao(() => this.service.cancelAll());
  }

  repetirFalhas(): Promise<void> {
    return this.acao(() => this.service.resumeAll(['FAILED']));
  }

  retomarInterrompidos(): Promise<void> {
    return this.acao(() => this.service.resumeAll(['INTERRUPTED', 'CANCELED']));
  }
}
