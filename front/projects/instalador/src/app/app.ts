import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from './api';
import { Desinstalacao } from './desinstalacao/desinstalacao';
import { Inicio } from './inicio/inicio';
import { Instalacao } from './instalacao/instalacao';
import { Progresso } from './progresso/progresso';
import { Quiz } from './quiz';

type Modo =
  'carregando' | 'inicio' | 'instalar' | 'progresso' | 'desinstalar' | 'encerrado' | 'erro';

@Component({
  selector: 'm2m-instalador',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    TranslocoPipe,
    Inicio,
    Instalacao,
    Progresso,
    Desinstalacao,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private api = inject(InstaladorApi);
  readonly quiz = inject(Quiz);
  readonly modo = signal<Modo>('carregando');
  readonly erro = signal('');

  async ngOnInit(): Promise<void> {
    try {
      const e = await this.api.estado();
      this.quiz.iniciar(e);
      // instalação já iniciada (página recarregada): volta ao progresso
      this.modo.set(e.instalacao ? 'progresso' : 'inicio');
    } catch (e) {
      this.erro.set(mensagemErro(e));
      this.modo.set('erro');
    }
  }

  async encerrar(): Promise<void> {
    try {
      await this.api.encerrar();
    } finally {
      this.modo.set('encerrado');
    }
  }
}
