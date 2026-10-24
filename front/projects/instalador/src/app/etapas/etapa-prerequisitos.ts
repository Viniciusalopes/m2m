import { Component, inject, OnInit, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from '../api';
import { EstadoItem, Verificacao } from '../modelo';
import { Quiz } from '../quiz';
import { CartaoComando } from '../ui/cartao-comando';

const ICONES: Record<EstadoItem, string> = {
  atendido: 'check_circle',
  pendente: 'error',
  opcional: 'warning',
};

/** Pré-requisitos: itens atendidos, pendentes e opcionais, com os comandos para o que faltar. */
@Component({
  selector: 'm2m-etapa-prerequisitos',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, TranslocoPipe, CartaoComando],
  templateUrl: './etapa-prerequisitos.html',
  styleUrl: './etapas.scss',
})
export class EtapaPrerequisitos implements OnInit {
  private api = inject(InstaladorApi);
  private quiz = inject(Quiz);
  readonly verificada = output<Verificacao>();
  readonly verificacao = signal<Verificacao | null>(null);
  readonly verificando = signal(false);
  readonly erro = signal('');
  readonly icones = ICONES;

  ngOnInit(): void {
    void this.verificar();
  }

  async verificar(): Promise<void> {
    this.verificando.set(true);
    this.erro.set('');
    try {
      const p = this.quiz.estado()?.padroes;
      const portas = p
        ? [p.containers.portaHttp, p.containers.portaHttps, p.logs.portaGrafana]
        : [];
      const v = await this.api.prerequisitos(p?.containers.diretorio ?? '', portas);
      this.verificacao.set(v);
      this.verificada.emit(v);
    } catch (e) {
      this.erro.set(mensagemErro(e));
    } finally {
      this.verificando.set(false);
    }
  }
}
