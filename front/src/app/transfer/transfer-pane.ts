import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine } from '../machines/machines.service';
import { BytesPipe } from '../ui/bytes.pipe';
import { ConfirmDialog } from '../ui/confirm-dialog';
import { PromptDialog } from '../ui/prompt-dialog';
import { FilterDialog } from './filter-dialog';
import {
  CompareState,
  Entry,
  FilterDefinition,
  Listing,
  TransferService,
} from './transfer.service';

/** O que um painel entrega ao soltar itens vindos do outro painel. */
export interface Soltura {
  srcMachineId: number;
  paths: string[];
  /** Diretório de destino neste painel (a pasta onde se soltou, ou a pasta aberta). */
  dstDir: string;
}

const MIME = 'application/x-m2m-transfer';

/** Um painel da tela de transferência: máquina, barra de caminho, lista, filtro, menu de contexto e arrastar. */
@Component({
  selector: 'm2m-transfer-pane',
  imports: [
    FormsModule,
    DatePipe,
    BytesPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    TranslocoPipe,
  ],
  template: `
    <section
      class="painel"
      [attr.aria-label]="titulo()"
      (dragover)="aoArrastarSobre($event)"
      (drop)="aoSoltar($event, null)"
    >
      <header class="topo">
        <mat-form-field appearance="outline" class="maquina" subscriptSizing="dynamic">
          <mat-label>{{ titulo() }}</mat-label>
          <mat-select [ngModel]="maquinaId()" (ngModelChange)="trocarMaquina($event)">
            @for (m of maquinas(); track m.id) {
              <mat-option [value]="m.id">{{ m.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <div class="barra">
          <button
            mat-icon-button
            type="button"
            (click)="subir()"
            [disabled]="!listagem()?.parent"
            [attr.aria-label]="'transferencia.subir' | transloco"
          >
            <mat-icon>arrow_upward</mat-icon>
          </button>
          <input
            class="caminho"
            [ngModel]="digitado()"
            (ngModelChange)="digitado.set($event)"
            (keydown.enter)="ir(digitado())"
            [attr.aria-label]="'transferencia.caminho' | transloco"
          />
          <button
            mat-icon-button
            type="button"
            (click)="carregar(path(), true)"
            [attr.aria-label]="'transferencia.atualizar' | transloco"
          >
            <mat-icon>refresh</mat-icon>
          </button>
          <button
            mat-icon-button
            type="button"
            (click)="editarFiltro()"
            [class.ativo]="!!filtro()"
            [attr.aria-label]="'transferencia.filtro.botao' | transloco"
          >
            <mat-icon>filter_alt</mat-icon>
          </button>
        </div>
        @if (filtro(); as f) {
          <p class="indicador" role="status">
            <mat-icon aria-hidden="true">filter_alt</mat-icon>
            {{ 'transferencia.filtro.ativo' | transloco: { n: listagem()?.hidden ?? 0 } }}
          </p>
        }
        @if (erro(); as e) {
          <p class="erro" role="alert">
            <mat-icon aria-hidden="true">error</mat-icon>
            {{ 'erro.' + e.codigo | transloco: e.parametros }}
          </p>
        }
      </header>

      <div class="rolagem" (contextmenu)="menuContexto($event, null)">
        <table class="lista">
          <thead>
            <tr>
              <th>{{ 'transferencia.col.nome' | transloco }}</th>
              <th class="num">{{ 'transferencia.col.tamanho' | transloco }}</th>
              <th>{{ 'transferencia.col.data' | transloco }}</th>
              <th>{{ 'transferencia.col.permissoes' | transloco }}</th>
              @if (estados()) {
                <th>{{ 'transferencia.col.diferenca' | transloco }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (e of visiveis(); track e.path) {
              <tr
                [class.selecionado]="selecao().has(e.path)"
                [class]="classeEstado(e)"
                tabindex="0"
                draggable="true"
                (click)="clicar(e, $event)"
                (dblclick)="abrir(e)"
                (keydown.enter)="abrir(e)"
                (contextmenu)="menuContexto($event, e)"
                (dragstart)="aoComecarArrastar($event, e)"
                (dragover)="aoArrastarSobre($event)"
                (drop)="aoSoltar($event, e)"
              >
                <td class="nome">
                  <mat-icon aria-hidden="true">{{ icone(e) }}</mat-icon>
                  <span>{{ e.name }}</span>
                  @if (e.linkTarget) {
                    <span class="alvo">→ {{ e.linkTarget }}</span>
                  }
                </td>
                <td class="num">{{ e.type === 'FILE' ? (e.size | bytes) : '' }}</td>
                <td>{{ e.mtime | date: 'dd/MM/yyyy HH:mm' }}</td>
                <td class="perm">{{ permissoes(e.mode) }}</td>
                @if (estados()) {
                  <td [class]="rotuloEstado(e) ? 'diferenca cor-' + classeEstado(e) : 'diferenca'">
                    @if (rotuloEstado(e); as r) {
                      <span class="estado">{{ 'transferencia.comparacao.' + r | transloco }}</span>
                    }
                  </td>
                }
              </tr>
            } @empty {
              <tr>
                <td [attr.colspan]="estados() ? 5 : 4" class="vazio">
                  {{ 'transferencia.vazio' | transloco }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <footer class="rodape">
        {{ 'transferencia.resumo' | transloco: { n: visiveis().length, sel: selecao().size } }}
      </footer>
    </section>

    <div
      class="gatilho-menu"
      [style.left.px]="menuX()"
      [style.top.px]="menuY()"
      [matMenuTriggerFor]="menu"
      #gatilho="matMenuTrigger"
    ></div>
    <mat-menu #menu="matMenu">
      <button mat-menu-item (click)="enviar.emit()" [disabled]="!selecao().size">
        <mat-icon>send</mat-icon>{{ 'transferencia.menu.transferir' | transloco }}
      </button>
      <button mat-menu-item (click)="criarPasta()">
        <mat-icon>create_new_folder</mat-icon>{{ 'transferencia.menu.criar-pasta' | transloco }}
      </button>
      <button mat-menu-item (click)="renomear()" [disabled]="selecao().size !== 1">
        <mat-icon>drive_file_rename_outline</mat-icon
        >{{ 'transferencia.menu.renomear' | transloco }}
      </button>
      <button mat-menu-item (click)="excluir()" [disabled]="!selecao().size">
        <mat-icon>delete</mat-icon>{{ 'transferencia.menu.excluir' | transloco }}
      </button>
      <button mat-menu-item (click)="carregar(path(), true)">
        <mat-icon>refresh</mat-icon>{{ 'transferencia.atualizar' | transloco }}
      </button>
    </mat-menu>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .painel {
      display: flex;
      flex-direction: column;
      height: 100%;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 0.75rem;
      background: var(--mat-sys-surface);
      overflow: hidden;
    }
    .topo {
      padding: 0.5rem 0.75rem 0;
    }
    .maquina {
      width: 100%;
    }
    .barra {
      display: flex;
      align-items: center;
      gap: 0.25rem;
    }
    .caminho {
      flex: 1;
      min-width: 0;
      padding: 0.375rem 0.625rem;
      border: 1px solid var(--mat-sys-outline);
      border-radius: 0.375rem;
      background: transparent;
      color: inherit;
      font: inherit;
    }
    .ativo {
      color: var(--m2m-warn);
    }
    .indicador,
    .erro {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      margin: 0.25rem 0;
      font-size: 0.9em;
    }
    .indicador {
      color: var(--m2m-warn);
    }
    .erro {
      color: var(--m2m-fail);
    }
    .rolagem {
      flex: 1;
      min-height: 17.5rem;
      max-height: 56vh;
      overflow: auto;
    }
    .lista {
      width: 100%;
      border-collapse: collapse;
    }
    .lista th {
      position: sticky;
      top: 0;
      background: var(--mat-sys-surface-container);
      text-align: left;
      padding: 0.375rem 0.625rem;
      font: var(--mat-sys-label-large);
    }
    .lista td {
      padding: 0.25rem 0.625rem;
      white-space: nowrap;
    }
    .lista tr {
      cursor: default;
    }
    .lista tbody tr:hover {
      background: var(--mat-sys-surface-container-low);
    }
    .lista tr.selecionado {
      background: var(--mat-sys-secondary-container);
    }
    .nome {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      max-width: 20rem;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .nome mat-icon {
      font-size: 1.25rem;
      width: 1.25rem;
      height: 1.25rem;
    }
    .alvo {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.85em;
    }
    .num {
      text-align: right;
    }
    .perm {
      font-family: monospace;
    }
    .vazio {
      text-align: center;
      color: var(--mat-sys-on-surface-variant);
      padding: 1.5rem;
    }
    .rodape {
      padding: 0.25rem 0.75rem;
      font-size: 0.85em;
      color: var(--mat-sys-on-surface-variant);
    }
    .gatilho-menu {
      position: fixed;
      width: 0;
      height: 0;
    }
    tr.dif-so-aqui {
      box-shadow: inset 0.25rem 0 0 var(--m2m-ok-container);
    }
    tr.dif-mais-novo {
      box-shadow: inset 0.25rem 0 0 var(--m2m-info-container);
    }
    tr.dif-mais-velho {
      box-shadow: inset 0.25rem 0 0 var(--m2m-warn-container);
    }
    tr.dif-tamanho {
      box-shadow: inset 0.25rem 0 0 var(--m2m-fail-container);
    }
    .estado {
      font-size: 0.85em;
    }
    td.diferenca {
      color: var(--mat-sys-on-surface);
    }
  `,
})
export class TransferPane {
  private service = inject(TransferService);
  private dialog = inject(MatDialog);

  readonly titulo = input.required<string>();
  readonly maquinas = input.required<Machine[]>();
  readonly estados = input<Record<string, CompareState> | null>(null);
  readonly ocultarIdenticos = input(false);
  /** Nomes visíveis pelo filtro de diferenças (nulo = sem filtro). */
  readonly nomesDif = input<ReadonlySet<string> | null>(null);
  readonly mostrarOcultos = input(true);

  /** Navegação feita pelo usuário (não a sincronizada): alimenta a navegação sincronizada. */
  readonly navegou = output<string>();
  readonly enviar = output<void>();
  readonly soltou = output<Soltura>();
  /** Algo mudou na pasta aberta (para atualizar a comparação). */
  readonly mudou = output<void>();

  readonly maquinaId = signal<number | null>(null);
  readonly path = signal<string>('');
  readonly digitado = signal('');
  readonly listagem = signal<Listing | null>(null);
  readonly filtro = signal<FilterDefinition | null>(null);
  readonly erro = signal<ApiErro | null>(null);
  readonly selecao = signal<Set<string>>(new Set());
  readonly menuX = signal(0);
  readonly menuY = signal(0);
  private ancora: string | null = null;
  private readonly gatilho = viewChild.required<MatMenuTrigger>('gatilho');

  readonly visiveis = computed<Entry[]>(() => {
    const l = this.listagem();
    if (!l) {
      return [];
    }
    const estados = this.estados();
    const nomes = this.nomesDif();
    return l.items.filter((e) => {
      if (!this.mostrarOcultos() && e.name.startsWith('.')) {
        return false;
      }
      if (nomes && !nomes.has(e.name)) {
        return false;
      }
      return !(this.ocultarIdenticos() && estados?.[e.name] === 'SAME');
    });
  });

  readonly selecionados = computed<Entry[]>(() =>
    (this.listagem()?.items ?? []).filter((e) => this.selecao().has(e.path)),
  );

  // ---- carregar e navegar ----

  /** Abre a máquina no diretório inicial dela. */
  async trocarMaquina(id: number): Promise<void> {
    this.maquinaId.set(id);
    this.listagem.set(null);
    await this.carregar(null, true);
  }

  /** Lista um diretório; {@code usuario} indica navegação do usuário (propaga para a navegação sincronizada). */
  async carregar(path: string | null, usuario: boolean): Promise<boolean> {
    const id = this.maquinaId();
    if (id === null) {
      return false;
    }
    const anterior = this.path();
    try {
      const l = await this.service.list(id, path, this.filtro());
      this.listagem.set(l);
      this.path.set(l.path);
      this.digitado.set(l.path);
      this.selecao.set(new Set());
      this.ancora = null;
      this.erro.set(null);
      if (usuario && l.path !== anterior) {
        this.navegou.emit(l.path);
      }
      this.mudou.emit();
      return true;
    } catch (e) {
      this.erro.set(apiErro(e));
      this.digitado.set(anterior);
      return false;
    }
  }

  /** Navegação disparada pelo outro painel (sincronizada): não volta a propagar. */
  irSilencioso(path: string): Promise<boolean> {
    return this.carregar(path, false);
  }

  async ir(path: string): Promise<void> {
    await this.carregar(path.trim() || null, true);
  }

  async subir(): Promise<void> {
    const pai = this.listagem()?.parent;
    if (pai) {
      await this.carregar(pai, true);
    }
  }

  async abrir(e: Entry): Promise<void> {
    if (e.type === 'DIRECTORY') {
      await this.carregar(e.path, true);
    }
  }

  async editarFiltro(): Promise<void> {
    const r = await firstValueFrom(
      this.dialog
        .open(FilterDialog, {
          data: { definition: this.filtro() },
          width: '45rem',
          maxWidth: '95vw',
        })
        .afterClosed(),
    );
    if (r === undefined) {
      return;
    }
    this.filtro.set(r);
    await this.carregar(this.path(), false);
  }

  // ---- seleção ----

  clicar(e: Entry, ev: MouseEvent): void {
    const atual = new Set(this.selecao());
    if (ev.shiftKey && this.ancora) {
      const itens = this.visiveis();
      const a = itens.findIndex((x) => x.path === this.ancora);
      const b = itens.findIndex((x) => x.path === e.path);
      atual.clear();
      for (let i = Math.min(a, b); i <= Math.max(a, b); i++) {
        atual.add(itens[i].path);
      }
    } else if (ev.ctrlKey || ev.metaKey) {
      if (atual.has(e.path)) {
        atual.delete(e.path);
      } else {
        atual.add(e.path);
      }
      this.ancora = e.path;
    } else {
      atual.clear();
      atual.add(e.path);
      this.ancora = e.path;
    }
    this.selecao.set(atual);
  }

  // ---- menu de contexto ----

  menuContexto(ev: MouseEvent, e: Entry | null): void {
    ev.preventDefault();
    ev.stopPropagation();
    if (e && !this.selecao().has(e.path)) {
      this.selecao.set(new Set([e.path]));
    }
    this.menuX.set(ev.clientX);
    this.menuY.set(ev.clientY);
    this.gatilho().openMenu();
  }

  async criarPasta(): Promise<void> {
    const nome = await firstValueFrom(
      this.dialog
        .open(PromptDialog, {
          data: {
            titulo: 'transferencia.criar-pasta.titulo',
            rotulo: 'transferencia.criar-pasta.nome',
          },
          width: '26.25rem',
        })
        .afterClosed(),
    );
    if (!nome) {
      return;
    }
    await this.executar(() => this.service.mkdir(this.maquinaId()!, this.juntar(nome)));
  }

  async renomear(): Promise<void> {
    const e = this.selecionados()[0];
    if (!e) {
      return;
    }
    const nome = await firstValueFrom(
      this.dialog
        .open(PromptDialog, {
          data: {
            titulo: 'transferencia.renomear.titulo',
            rotulo: 'transferencia.renomear.nome',
            valor: e.name,
          },
          width: '26.25rem',
        })
        .afterClosed(),
    );
    if (!nome || nome === e.name) {
      return;
    }
    await this.executar(() => this.service.rename(this.maquinaId()!, e.path, this.juntar(nome)));
  }

  /** Exclusão: antes de apagar, mostra quantos itens serão excluídos (diretórios contam o que há dentro). */
  async excluir(): Promise<void> {
    const alvos = this.selecionados();
    if (!alvos.length) {
      return;
    }
    this.erro.set(null);
    let total = 0;
    try {
      for (const a of alvos) {
        total += (await this.service.count(this.maquinaId()!, a.path)).items;
      }
    } catch (e) {
      this.erro.set(apiErro(e));
      return;
    }
    const ok = await firstValueFrom(
      this.dialog
        .open(ConfirmDialog, {
          data: {
            titulo: 'transferencia.excluir.titulo',
            mensagem: 'transferencia.excluir.mensagem',
            confirmar: 'comum.excluir',
            params: { itens: total },
            perigo: true,
          },
        })
        .afterClosed(),
    );
    if (!ok) {
      return;
    }
    await this.executar(async () => {
      for (const a of alvos) {
        await this.service.remove(this.maquinaId()!, a.path);
      }
    });
  }

  private juntar(nome: string): string {
    const base = this.path();
    return (base === '/' ? '' : base) + '/' + nome;
  }

  private async executar(acao: () => Promise<unknown>): Promise<void> {
    this.erro.set(null);
    try {
      await acao();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
    await this.carregar(this.path(), false);
  }

  // ---- arrastar e soltar ----

  aoComecarArrastar(ev: DragEvent, e: Entry): void {
    if (!this.selecao().has(e.path)) {
      this.selecao.set(new Set([e.path]));
    }
    const paths = this.selecionados().map((x) => x.path);
    ev.dataTransfer?.setData(MIME, JSON.stringify({ machineId: this.maquinaId(), paths }));
    if (ev.dataTransfer) {
      ev.dataTransfer.effectAllowed = 'copy';
    }
  }

  aoArrastarSobre(ev: DragEvent): void {
    if (ev.dataTransfer?.types.includes(MIME)) {
      ev.preventDefault();
    }
  }

  aoSoltar(ev: DragEvent, alvo: Entry | null): void {
    const bruto = ev.dataTransfer?.getData(MIME);
    if (!bruto) {
      return;
    }
    ev.preventDefault();
    ev.stopPropagation();
    const dados = JSON.parse(bruto) as { machineId: number; paths: string[] };
    const dstDir = alvo?.type === 'DIRECTORY' ? alvo.path : this.path();
    this.soltou.emit({ srcMachineId: dados.machineId, paths: dados.paths, dstDir });
  }

  // ---- apresentação ----

  icone(e: Entry): string {
    switch (e.type) {
      case 'DIRECTORY':
        return 'folder';
      case 'SYMLINK':
        return 'link';
      case 'SPECIAL':
        return 'block';
      default:
        return 'description';
    }
  }

  permissoes(mode: number | null): string {
    if (mode === null || mode === undefined) {
      return '';
    }
    const bits = 'rwxrwxrwx';
    let s = '';
    for (let i = 0; i < 9; i++) {
      s += mode & (1 << (8 - i)) ? bits[i] : '-';
    }
    return s;
  }

  rotuloEstado(e: Entry): string | null {
    const s = this.estados()?.[e.name];
    return !s || s === 'SAME' || s === 'ABSENT' ? null : s;
  }

  classeEstado(e: Entry): string {
    switch (this.estados()?.[e.name]) {
      case 'ONLY_HERE':
        return 'dif-so-aqui';
      case 'NEWER':
        return 'dif-mais-novo';
      case 'OLDER':
        return 'dif-mais-velho';
      case 'SIZE_DIFFERENT':
      case 'TYPE_DIFFERENT':
        return 'dif-tamanho';
      default:
        return '';
    }
  }
}
