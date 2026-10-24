import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from '../api';
import { EtapaContainers } from '../etapas/etapa-containers';
import { EtapaLogs } from '../etapas/etapa-logs';
import { EtapaMaquinas } from '../etapas/etapa-maquinas';
import { EtapaPrerequisitos } from '../etapas/etapa-prerequisitos';
import { EtapaRede } from '../etapas/etapa-rede';
import { EtapaRevisao } from '../etapas/etapa-revisao';
import { EtapaSeguranca } from '../etapas/etapa-seguranca';
import { EtapaUsuarios } from '../etapas/etapa-usuarios';
import { Erros, Verificacao } from '../modelo';
import { Etapa, ETAPAS, Quiz, VALIDADAS } from '../quiz';

/** Quiz de instalação: etapas em ordem; avançar valida no instalador, voltar nunca perde respostas. */
@Component({
  selector: 'm2m-instalacao',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatStepperModule,
    TranslocoPipe,
    EtapaPrerequisitos,
    EtapaContainers,
    EtapaSeguranca,
    EtapaUsuarios,
    EtapaRede,
    EtapaMaquinas,
    EtapaLogs,
    EtapaRevisao,
  ],
  templateUrl: './instalacao.html',
  styleUrl: './instalacao.scss',
})
export class Instalacao {
  private api = inject(InstaladorApi);
  readonly quiz = inject(Quiz);
  readonly iniciado = output();
  readonly voltar = output();
  readonly stepper = viewChild.required(MatStepper);

  readonly etapas = ETAPAS;
  readonly prerequisitosBloqueiam = signal(true);
  readonly validando = signal(false);
  /** Chave da mensagem de erro do quiz. */
  readonly erro = signal('');
  /** Mensagem de falha vinda do instalador. */
  readonly falha = signal('');

  verificados(v: Verificacao): void {
    this.prerequisitosBloqueiam.set(v.bloqueia);
  }

  /** Confere a etapa no formulário e no instalador antes de seguir. */
  async avancar(etapa: Etapa): Promise<void> {
    this.erro.set('');
    this.falha.set('');
    if (etapa === 'prerequisitos') {
      if (this.prerequisitosBloqueiam()) {
        return;
      }
    } else if (VALIDADAS.has(etapa)) {
      const g = this.quiz.grupo(etapa)!;
      g.markAllAsTouched();
      if (g.invalid) {
        this.erro.set('quiz.corrija');
        return;
      }
      this.validando.set(true);
      try {
        const erros = await this.api.validar(etapa, this.quiz.respostas(), this.quiz.segredos());
        if (Object.keys(erros).length > 0) {
          this.quiz.aplicarErros(etapa, erros);
          this.erro.set('quiz.corrija');
          return;
        }
      } catch (e) {
        this.falha.set(mensagemErro(e));
        return;
      } finally {
        this.validando.set(false);
      }
      this.quiz.editar(etapa);
    }
    this.quiz.confirmar(etapa);
    this.seguir();
  }

  /** Marca a etapa atual como concluída no stepper (linear) antes de seguir, sem esperar a detecção de mudanças. */
  private seguir(): void {
    const passo = this.stepper().selected;
    if (passo) {
      passo.completed = true;
    }
    this.stepper().next();
  }

  async pular(etapa: Etapa): Promise<void> {
    this.erro.set('');
    this.falha.set('');
    this.quiz.pular(etapa);
    // os padrões também precisam servir nesta máquina (ex.: porta 80 ocupada)
    let erros: Erros;
    try {
      erros = await this.api.validar(etapa, this.quiz.respostas(), this.quiz.segredos());
    } catch (e) {
      this.desconfirmar(etapa);
      this.falha.set(mensagemErro(e));
      return;
    }
    if (Object.keys(erros).length > 0) {
      this.quiz.editar(etapa);
      this.quiz.aplicarErros(etapa, erros);
      this.desconfirmar(etapa);
      this.erro.set('quiz.padroes-invalidos');
      return;
    }
    this.seguir();
  }

  private desconfirmar(etapa: Etapa): void {
    this.quiz.confirmadas.update((s) => {
      const n = new Set(s);
      n.delete(etapa);
      return n;
    });
  }

  irPara(etapa: Etapa): void {
    this.stepper().selectedIndex = ETAPAS.indexOf(etapa);
  }

  async iniciar(): Promise<void> {
    this.erro.set('');
    this.falha.set('');
    this.validando.set(true);
    try {
      await this.api.instalar(
        this.quiz.respostas(),
        this.quiz.segredos(),
        !!this.quiz.importacao()?.configuracao,
      );
      this.iniciado.emit();
    } catch (e) {
      // campos que deixaram de servir desde a etapa (ex.: porta ocupada agora): volta à primeira com erro
      const etapas =
        e instanceof HttpErrorResponse ? (e.error?.etapas as Record<string, Erros>) : null;
      if (etapas) {
        const primeira = ETAPAS.find((et) => etapas[et]);
        for (const [et, erros] of Object.entries(etapas)) {
          this.quiz.aplicarErros(et as Etapa, erros);
          this.desconfirmar(et as Etapa);
        }
        if (primeira) {
          this.irPara(primeira);
        }
        this.erro.set('quiz.corrija');
      } else {
        this.falha.set(mensagemErro(e));
      }
    } finally {
      this.validando.set(false);
    }
  }
}
