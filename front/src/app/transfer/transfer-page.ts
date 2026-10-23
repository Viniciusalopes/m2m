import { AfterViewInit, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine, MachinesService } from '../machines/machines.service';
import { ConflictAnswer, ConflictDialog } from './conflict-dialog';
import { FilterConfirmDialog } from './filter-confirm-dialog';
import { Soltura, TransferPane } from './transfer-pane';
import { TransferQueue } from './transfer-queue';
import {
  CompareState,
  ConflictAction,
  FilterDefinition,
  PlanRequest,
  TransferService,
} from './transfer.service';

type FiltroDif = 'TODOS' | 'ONLY_HERE' | 'NEWER' | 'OLDER' | 'SIZE_DIFFERENT';

/** Tela de transferência (D22): dois painéis, ➜/⬅, arrastar, comparação, navegação sincronizada e fila. */
@Component({
  selector: 'm2m-transfer-page',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    TransferPane,
    TransferQueue,
    TranslocoPipe,
  ],
  template: `
    <section class="m2m-page transferencia">
      <h1>{{ 'transferencia.titulo' | transloco }}</h1>

      <div class="barra" role="toolbar" [attr.aria-label]="'transferencia.ferramentas' | transloco">
        <mat-slide-toggle
          [checked]="mostrarOcultos()"
          (change)="mostrarOcultos.set($event.checked)"
          >{{ 'transferencia.mostrar-ocultos' | transloco }}</mat-slide-toggle
        >
        <mat-slide-toggle
          [checked]="sincronizada()"
          (change)="alternarSincronizada($event.checked)"
          >{{ 'transferencia.navegacao-sincronizada' | transloco }}</mat-slide-toggle
        >
        <mat-slide-toggle [checked]="comparar()" (change)="alternarComparacao($event.checked)">{{
          'transferencia.comparar' | transloco
        }}</mat-slide-toggle>
        @if (comparar()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="modo">
            <mat-label>{{ 'transferencia.comparacao.modo' | transloco }}</mat-label>
            <mat-select [ngModel]="modo()" (ngModelChange)="mudarModo($event)">
              <mat-option value="MTIME">{{
                'transferencia.comparacao.por-data' | transloco
              }}</mat-option>
              <mat-option value="SIZE">{{
                'transferencia.comparacao.por-tamanho' | transloco
              }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="dif">
            <mat-label>{{ 'transferencia.comparacao.filtrar' | transloco }}</mat-label>
            <mat-select
              [ngModel]="filtroDif()"
              (ngModelChange)="filtroDif.set($event)"
              panelWidth=""
            >
              <mat-option value="TODOS">{{
                'transferencia.comparacao.TODOS' | transloco
              }}</mat-option>
              <mat-option value="ONLY_HERE" class="cor-dif-so-aqui">{{
                'transferencia.comparacao.ONLY_HERE' | transloco
              }}</mat-option>
              <mat-option value="NEWER" class="cor-dif-mais-novo">{{
                'transferencia.comparacao.NEWER' | transloco
              }}</mat-option>
              <mat-option value="OLDER" class="cor-dif-mais-velho">{{
                'transferencia.comparacao.OLDER' | transloco
              }}</mat-option>
              <mat-option value="SIZE_DIFFERENT" class="cor-dif-tamanho">{{
                'transferencia.comparacao.SIZE_DIFFERENT' | transloco
              }}</mat-option>
            </mat-select>
          </mat-form-field>
          @if (modo() === 'MTIME') {
            <mat-form-field appearance="outline" subscriptSizing="dynamic" class="tolerancia">
              <mat-label>{{ 'transferencia.comparacao.tolerancia' | transloco }}</mat-label>
              <input
                matInput
                type="number"
                min="0"
                [ngModel]="tolerancia()"
                (ngModelChange)="mudarTolerancia($event)"
              />
            </mat-form-field>
          }
          <mat-checkbox
            [checked]="ocultarIdenticos()"
            (change)="ocultarIdenticos.set($event.checked)"
            >{{ 'transferencia.comparacao.ocultar-identicos' | transloco }}</mat-checkbox
          >
        }
      </div>

      @if (comparar()) {
        <ul class="legenda" [attr.aria-label]="'transferencia.comparacao.legenda' | transloco">
          <li>
            <span class="cor cor-dif-so-aqui"></span
            >{{ 'transferencia.comparacao.ONLY_HERE' | transloco }}
          </li>
          <li>
            <span class="cor cor-dif-mais-novo"></span
            >{{ 'transferencia.comparacao.NEWER' | transloco }}
          </li>
          <li>
            <span class="cor cor-dif-mais-velho"></span
            >{{ 'transferencia.comparacao.OLDER' | transloco }}
          </li>
          <li>
            <span class="cor cor-dif-tamanho"></span
            >{{ 'transferencia.comparacao.SIZE_DIFFERENT' | transloco }}
          </li>
        </ul>
      }

      @if (aviso(); as a) {
        <p class="aviso" role="status">
          <mat-icon aria-hidden="true">info</mat-icon>{{ a | transloco }}
        </p>
      }
      @if (erro(); as e) {
        <p class="erro" role="alert">
          <mat-icon aria-hidden="true">error</mat-icon>
          {{ 'erro.' + e.codigo | transloco: e.parametros }}
          @if (e.parametros?.['jobId']; as jobId) {
            <a [routerLink]="['/jobs', jobId]">{{ 'transferencia.ver-job' | transloco }}</a>
          }
        </p>
      }
      @if (mensagem(); as m) {
        <p class="ok" role="status">
          <mat-icon aria-hidden="true">check_circle</mat-icon>{{ m.chave | transloco: m.params }}
        </p>
      }

      <div class="paineis">
        <m2m-transfer-pane
          #esquerdo
          class="painel"
          [titulo]="'transferencia.painel-esquerdo' | transloco"
          [maquinas]="maquinas()"
          [estados]="estadosEsq()"
          [nomesDif]="nomesDif()"
          [ocultarIdenticos]="ocultarIdenticos()"
          [mostrarOcultos]="mostrarOcultos()"
          (navegou)="aoNavegar('esq', $event)"
          (enviar)="enviar('esq')"
          (soltou)="soltar('esq', $event)"
          (mudou)="atualizarComparacao()"
        />
        <div class="controles">
          <button
            mat-flat-button
            type="button"
            (click)="enviar('esq')"
            [attr.aria-label]="'transferencia.enviar-para-direita' | transloco"
          >
            <mat-icon>arrow_forward</mat-icon>
          </button>
          <button
            mat-flat-button
            type="button"
            (click)="enviar('dir')"
            [attr.aria-label]="'transferencia.enviar-para-esquerda' | transloco"
          >
            <mat-icon>arrow_back</mat-icon>
          </button>
        </div>
        <m2m-transfer-pane
          #direito
          class="painel"
          [titulo]="'transferencia.painel-direito' | transloco"
          [maquinas]="maquinas()"
          [estados]="estadosDir()"
          [nomesDif]="nomesDif()"
          [ocultarIdenticos]="ocultarIdenticos()"
          [mostrarOcultos]="mostrarOcultos()"
          (navegou)="aoNavegar('dir', $event)"
          (enviar)="enviar('dir')"
          (soltou)="soltar('dir', $event)"
          (mudou)="atualizarComparacao()"
        />
      </div>

      <m2m-transfer-queue #fila (terminou)="aoTerminar()" />
    </section>
  `,
  styles: `
    .transferencia {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      max-width: none;
    }
    .barra {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
      align-items: center;
    }
    .modo {
      width: 15rem;
    }
    .dif {
      width: 15rem;
    }
    .tolerancia {
      width: 10rem;
    }
    .legenda {
      display: flex;
      gap: 1rem;
      flex-wrap: wrap;
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .legenda li {
      display: flex;
      align-items: center;
      gap: 0.375rem;
    }
    .cor {
      display: inline-block;
      width: 0.875rem;
      height: 0.875rem;
      border-radius: 0.1875rem;
      border: 1px solid var(--mat-sys-outline-variant);
    }
    .paineis {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
      gap: 0.5rem;
      align-items: start;
    }
    .controles {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      justify-content: center;
      align-self: center;
    }
    .aviso,
    .erro,
    .ok {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      margin: 0;
    }
    .aviso {
      color: var(--m2m-warn);
    }
    .erro {
      color: var(--m2m-fail);
    }
    .ok {
      color: var(--m2m-ok);
    }
    @media (max-width: 56.25em) {
      .paineis {
        grid-template-columns: 1fr;
      }
      .controles {
        flex-direction: row;
        justify-content: center;
      }
    }
  `,
})
export class TransferPage implements AfterViewInit {
  private service = inject(TransferService);
  private machinesService = inject(MachinesService);
  private dialog = inject(MatDialog);

  private readonly esquerdo = viewChild.required<TransferPane>('esquerdo');
  private readonly direito = viewChild.required<TransferPane>('direito');
  private readonly fila = viewChild.required<TransferQueue>('fila');

  readonly maquinas = signal<Machine[]>([]);
  readonly mostrarOcultos = signal(true);
  readonly comparar = signal(true);
  readonly modo = signal<'SIZE' | 'MTIME'>('MTIME');
  readonly tolerancia = signal(0);
  readonly ocultarIdenticos = signal(false);
  readonly filtroDif = signal<FiltroDif>('TODOS');
  readonly sincronizada = signal(false);
  readonly estadosEsq = signal<Record<string, CompareState> | null>(null);
  readonly estadosDir = signal<Record<string, CompareState> | null>(null);
  readonly erro = signal<ApiErro | null>(null);
  readonly aviso = signal<string | null>(null);
  readonly mensagem = signal<{ chave: string; params?: Record<string, unknown> } | null>(null);
  private base: { esq: string; dir: string } | null = null;
  private recarregando: ReturnType<typeof setTimeout> | null = null;

  async ngAfterViewInit(): Promise<void> {
    // o carregamento das máquinas termina depois da primeira renderização dos painéis
    const todas = await this.machinesService.list();
    this.maquinas.set(todas);
    const local = todas.find((m) => m.protocol === 'LOCAL') ?? todas[0];
    const outra = todas.find((m) => m.id !== local?.id) ?? local;
    if (local) {
      await this.esquerdo().trocarMaquina(local.id);
    }
    if (outra) {
      await this.direito().trocarMaquina(outra.id);
    }
  }

  // ---- navegação sincronizada ----

  alternarSincronizada(ligar: boolean): void {
    this.aviso.set(null);
    if (ligar) {
      this.base = { esq: this.esquerdo().path(), dir: this.direito().path() };
    } else {
      this.base = null;
    }
    this.sincronizada.set(ligar);
  }

  private relativo(base: string, caminho: string): string | null {
    if (caminho === base) {
      return '';
    }
    const prefixo = base === '/' ? '/' : base + '/';
    return caminho.startsWith(prefixo) ? caminho.substring(prefixo.length) : null;
  }

  async aoNavegar(lado: 'esq' | 'dir', novo: string): Promise<void> {
    if (!this.sincronizada() || !this.base) {
      return;
    }
    const origem = lado === 'esq' ? this.base.esq : this.base.dir;
    const baseOutro = lado === 'esq' ? this.base.dir : this.base.esq;
    const outro = lado === 'esq' ? this.direito() : this.esquerdo();
    const rel = this.relativo(origem, novo);
    if (rel === null) {
      this.suspender('transferencia.sincronizada.suspensa-fora');
      return;
    }
    const alvo = rel === '' ? baseOutro : (baseOutro === '/' ? '' : baseOutro) + '/' + rel;
    if (!(await outro.irSilencioso(alvo))) {
      this.suspender('transferencia.sincronizada.suspensa-inexistente');
    }
  }

  private suspender(chave: string): void {
    this.sincronizada.set(false);
    this.base = null;
    this.aviso.set(chave);
  }

  // ---- comparação ----

  /** Nomes que casam com o filtro de diferenças em qualquer dos lados; {@code null} = sem filtro. */
  readonly nomesDif = computed<ReadonlySet<string> | null>(() => {
    const f = this.filtroDif();
    const esq = this.estadosEsq();
    const dir = this.estadosDir();
    if (f === 'TODOS' || !this.comparar() || !esq || !dir) {
      return null;
    }
    const casa = (s: CompareState | undefined) =>
      s === f || (f === 'SIZE_DIFFERENT' && s === 'TYPE_DIFFERENT');
    const nomes = new Set<string>();
    new Set([...Object.keys(esq), ...Object.keys(dir)]).forEach((n) => {
      if (casa(esq[n]) || casa(dir[n])) {
        nomes.add(n);
      }
    });
    return nomes;
  });

  alternarComparacao(ligar: boolean): void {
    this.comparar.set(ligar);
    if (!ligar) {
      this.filtroDif.set('TODOS');
    }
    void this.atualizarComparacao();
  }

  mudarModo(m: 'SIZE' | 'MTIME'): void {
    this.modo.set(m);
    void this.atualizarComparacao();
  }

  mudarTolerancia(v: number): void {
    this.tolerancia.set(Number(v) || 0);
    void this.atualizarComparacao();
  }

  async atualizarComparacao(): Promise<void> {
    if (!this.comparar()) {
      this.estadosEsq.set(null);
      this.estadosDir.set(null);
      return;
    }
    const e = this.esquerdo();
    const d = this.direito();
    if (e.maquinaId() === null || d.maquinaId() === null || !e.path() || !d.path()) {
      return;
    }
    try {
      const r = await this.service.compare(
        { machineId: e.maquinaId()!, path: e.path(), filter: e.filtro() },
        { machineId: d.maquinaId()!, path: d.path(), filter: d.filtro() },
        this.modo(),
        this.tolerancia() || null,
      );
      const esq: Record<string, CompareState> = {};
      const dir: Record<string, CompareState> = {};
      r.entries.forEach((x) => {
        esq[x.name] = x.leftState;
        dir[x.name] = x.rightState;
      });
      this.erro.set(null);
      this.estadosEsq.set(esq);
      this.estadosDir.set(dir);
    } catch (err) {
      // comparação recusada (ex.: filtros diferentes): desliga e explica
      this.erro.set(apiErro(err));
      this.comparar.set(false);
      this.estadosEsq.set(null);
      this.estadosDir.set(null);
    }
  }

  // ---- transferir ----

  enviar(de: 'esq' | 'dir'): Promise<void> {
    const src = de === 'esq' ? this.esquerdo() : this.direito();
    const dst = de === 'esq' ? this.direito() : this.esquerdo();
    const sel = src.selecionados();
    if (!sel.length || src.maquinaId() === null || dst.maquinaId() === null) {
      return Promise.resolve();
    }
    return this.iniciar(
      {
        srcMachineId: src.maquinaId()!,
        srcPaths: sel.map((s) => s.path),
        dstMachineId: dst.maquinaId()!,
        dstDir: dst.path(),
      },
      src.filtro(),
    );
  }

  /** Soltar no painel {@code destino} itens arrastados do outro painel. */
  soltar(destino: 'esq' | 'dir', s: Soltura): Promise<void> {
    const dst = destino === 'esq' ? this.esquerdo() : this.direito();
    const src = destino === 'esq' ? this.direito() : this.esquerdo();
    if (dst.maquinaId() === null) {
      return Promise.resolve();
    }
    return this.iniciar(
      {
        srcMachineId: s.srcMachineId,
        srcPaths: s.paths,
        dstMachineId: dst.maquinaId()!,
        dstDir: s.dstDir,
      },
      src.filtro(),
    );
  }

  private async iniciar(
    base: Pick<PlanRequest, 'srcMachineId' | 'srcPaths' | 'dstMachineId' | 'dstDir'>,
    filtroOrigem: FilterDefinition | null,
  ): Promise<void> {
    this.erro.set(null);
    this.mensagem.set(null);
    let req: PlanRequest = {
      ...base,
      applyFilters: false,
      filter: filtroOrigem,
      conflictDefault: null,
      decisions: null,
    };
    try {
      if (filtroOrigem) {
        const aplicar = await firstValueFrom(
          this.dialog
            .open(FilterConfirmDialog, {
              data: { request: req },
              width: '32.5rem',
              maxWidth: '95vw',
            })
            .afterClosed(),
        );
        if (aplicar === undefined) {
          return;
        }
        req = { ...req, applyFilters: aplicar };
      }
      const plano = await this.service.plan(req);
      if (plano.conflicts.length) {
        const decididos = await this.resolverConflitos(plano.conflicts);
        if (!decididos) {
          return;
        }
        req = { ...req, ...decididos };
      }
      const r = await this.service.start(req);
      this.mensagem.set({ chave: 'transferencia.iniciada', params: { n: r.items } });
      await this.fila().carregar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  /** Pergunta, conflito a conflito, o que fazer; "aplicar a todos" encerra as perguntas. */
  private async resolverConflitos(
    conflitos: {
      srcPath: string;
      dstPath: string;
      srcSize: number;
      srcMtime: number;
      dstSize: number;
      dstMtime: number;
    }[],
  ): Promise<{
    conflictDefault: ConflictAction | null;
    decisions: Record<string, ConflictAction>;
  } | null> {
    const decisions: Record<string, ConflictAction> = {};
    for (let i = 0; i < conflitos.length; i++) {
      const r = await firstValueFrom(
        this.dialog
          .open<ConflictDialog, unknown, ConflictAnswer>(ConflictDialog, {
            data: { conflict: conflitos[i], indice: i + 1, total: conflitos.length },
            width: '35rem',
            maxWidth: '95vw',
          })
          .afterClosed(),
      );
      if (!r) {
        return null;
      }
      if (r.applyToAll) {
        return { conflictDefault: r.action, decisions };
      }
      decisions[conflitos[i].dstPath] = r.action;
    }
    return { conflictDefault: null, decisions };
  }

  /** Terminou algum item da fila: atualiza os painéis (com um pequeno atraso para juntar vários fins). */
  aoTerminar(): void {
    if (this.recarregando) {
      return;
    }
    this.recarregando = setTimeout(() => {
      this.recarregando = null;
      void this.esquerdo().carregar(this.esquerdo().path(), false);
      void this.direito().carregar(this.direito().path(), false);
    }, 800);
  }
}
