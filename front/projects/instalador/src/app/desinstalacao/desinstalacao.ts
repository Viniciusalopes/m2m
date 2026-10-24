import { Component, computed, DestroyRef, inject, OnInit, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from '../api';
import { EstadoPasso, Localizada, ResultadoDesinstalacao } from '../modelo';
import { ICONES_PASSO } from '../progresso/progresso';
import { confirmacaoValida, Quiz } from '../quiz';
import { CartaoComando } from '../ui/cartao-comando';

/** Desinstalação: o que foi encontrado, o que remover, exportação antes, confirmação digitada e progresso. */
@Component({
  selector: 'm2m-desinstalacao',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    TranslocoPipe,
    CartaoComando,
  ],
  templateUrl: './desinstalacao.html',
  styleUrl: './desinstalacao.scss',
})
export class Desinstalacao implements OnInit {
  private api = inject(InstaladorApi);
  private destroy = inject(DestroyRef);
  readonly quiz = inject(Quiz);
  readonly voltar = output();
  readonly encerrar = output();

  readonly localizada = signal<Localizada | null>(null);
  readonly marcados = signal<ReadonlySet<string>>(new Set());
  readonly digitado = signal('');
  readonly senha = signal('');
  readonly exportado = signal(false);
  readonly removendo = signal(false);
  readonly estados = signal<Record<string, { estado: EstadoPasso; erro?: string }>>({});
  readonly resultado = signal<ResultadoDesinstalacao | null>(null);
  readonly falha = signal('');
  readonly icones = ICONES_PASSO;

  readonly palavra = computed(() => this.quiz.estado()?.confirmacao ?? 'DESINSTALAR');
  readonly podeRemover = computed(
    () =>
      confirmacaoValida(this.digitado(), this.palavra()) && !this.removendo() && !this.resultado(),
  );

  async ngOnInit(): Promise<void> {
    this.api
      .eventos()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe((e) => {
        if (e.tipo === 'item' && e.passo) {
          this.estados.update((s) => ({ ...s, [e.passo!]: { estado: e.estado!, erro: e.erro } }));
        } else if (e.tipo === 'fim' && this.removendo()) {
          void this.buscarResultado();
        }
      });
    try {
      const l = await this.api.desinstalacao();
      this.localizada.set(l);
      this.marcados.set(new Set(l.itens.filter((i) => i.padrao).map((i) => i.id)));
    } catch (e) {
      this.falha.set(mensagemErro(e));
    }
  }

  marcar(id: string, marcado: boolean): void {
    this.marcados.update((s) => {
      const n = new Set(s);
      if (marcado) {
        n.add(id);
      } else {
        n.delete(id);
      }
      return n;
    });
  }

  async exportar(): Promise<void> {
    this.falha.set('');
    try {
      await this.api.exportarInstalada(this.senha());
      this.exportado.set(true);
    } catch (e) {
      this.falha.set(mensagemErro(e));
    }
  }

  async remover(): Promise<void> {
    if (!this.podeRemover()) {
      return;
    }
    this.falha.set('');
    this.removendo.set(true);
    try {
      await this.api.desinstalar([...this.marcados()], this.digitado().trim());
    } catch (e) {
      this.removendo.set(false);
      this.falha.set(mensagemErro(e));
      return;
    }
    // o evento "fim" chega pelo SSE; a consulta periódica cobre um evento perdido
    while (this.removendo()) {
      await new Promise((r) => setTimeout(r, 2000));
      await this.buscarResultado();
    }
  }

  private async buscarResultado(): Promise<void> {
    const r = await this.api.resultadoDesinstalacao();
    if (r.resultado) {
      this.resultado.set(r.resultado);
      this.removendo.set(false);
    }
  }
}
