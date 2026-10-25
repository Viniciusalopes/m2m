import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, DestroyRef, inject, OnInit, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from '../api';
import { EstadoPasso, Evento, Situacao } from '../modelo';
import { Quiz } from '../quiz';

const MAX_LOG = 400;

export const ICONES_PASSO: Record<EstadoPasso, string> = {
  aguardando: 'radio_button_unchecked',
  executando: 'pending',
  concluido: 'check_circle',
  falhou: 'error',
};

/** Aplica um evento do SSE à situação, sem esperar a próxima consulta. */
export function aplicarEvento(s: Situacao, e: Evento): Situacao {
  if ((e.tipo !== 'passo' && e.tipo !== 'log') || !e.passo) {
    return s;
  }
  return {
    ...s,
    rodando: e.tipo === 'passo' && e.estado === 'executando' ? true : s.rodando,
    passos: s.passos.map((p) => {
      if (p.id !== e.passo) {
        return p;
      }
      if (e.tipo === 'passo') {
        return {
          ...p,
          estado: e.estado!,
          erro: e.erro,
          log: e.estado === 'executando' ? [] : p.log,
        };
      }
      return { ...p, log: [...(p.log ?? []), e.linha!].slice(-MAX_LOG) };
    }),
  };
}

/** Progresso de "Iniciar m2m" e, ao fim, a conclusão com o endereço, os comandos pendentes e a exportação. */
@Component({
  selector: 'm2m-progresso',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    TranslocoPipe,
  ],
  templateUrl: './progresso.html',
  styleUrl: './progresso.scss',
})
export class Progresso implements OnInit {
  private api = inject(InstaladorApi);
  private destroy = inject(DestroyRef);
  readonly quiz = inject(Quiz);
  readonly encerrar = output();

  readonly situacao = signal<Situacao | null>(null);
  readonly falha = signal('');
  readonly icones = ICONES_PASSO;
  /** Passo cujo log aparece: o em andamento, o que falhou ou o último concluído. */
  readonly ativo = computed(() => {
    const ps = this.situacao()?.passos ?? [];
    return (
      ps.find((p) => p.estado === 'executando') ??
      ps.find((p) => p.estado === 'falhou') ??
      [...ps].reverse().find((p) => p.estado === 'concluido')
    );
  });
  readonly falhou = computed(
    () => this.situacao()?.passos.some((p) => p.estado === 'falhou') ?? false,
  );

  readonly atalhos = signal<string[] | null>(null);
  readonly senha = signal('');
  readonly confirmacao = signal('');
  readonly exportado = signal(false);
  readonly exportando = signal(false);

  ngOnInit(): void {
    this.api
      .eventos()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe((e) => {
        if (e.tipo === 'fim') {
          void this.atualizar();
          return;
        }
        const s = this.situacao();
        if (s) {
          this.situacao.set(aplicarEvento(s, e));
        }
      });
    void this.atualizar();
  }

  async atualizar(): Promise<void> {
    try {
      this.situacao.set(await this.api.situacao());
    } catch (e) {
      this.falha.set(mensagemErro(e));
    }
  }

  async tentarDeNovo(): Promise<void> {
    this.falha.set('');
    try {
      this.situacao.set(await this.api.retomar());
    } catch (e) {
      this.falha.set(mensagemErro(e));
    }
  }

  async criarAtalhos(): Promise<void> {
    try {
      this.atalhos.set(await this.api.atalhos());
    } catch (e) {
      this.falha.set(mensagemErro(e));
    }
  }

  senhaValida(): boolean {
    const min = this.quiz.estado()?.minExportar ?? 12;
    return this.senha().length >= min && this.senha() === this.confirmacao();
  }

  /**
   * Exporta o .json. Com a página recarregada, as respostas do quiz se perderam: o arquivo é montado a partir do
   * .env instalado.
   */
  async exportar(): Promise<void> {
    this.falha.set('');
    this.exportando.set(true);
    try {
      if (this.quiz.confirmadas().size > 0) {
        await this.api.exportar(this.quiz.respostas(), this.quiz.segredos(), this.senha());
      } else {
        await this.api.desinstalacao();
        await this.api.exportarInstalada(this.senha());
      }
      this.exportado.set(true);
    } catch (e) {
      this.falha.set(mensagemErro(e));
    } finally {
      this.exportando.set(false);
    }
  }
}
