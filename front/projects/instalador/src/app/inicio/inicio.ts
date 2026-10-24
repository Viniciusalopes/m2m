import { Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { TranslocoPipe } from '@jsverse/transloco';
import { InstaladorApi, mensagemErro } from '../api';
import { Importado } from '../modelo';
import { Quiz } from '../quiz';

/** Início: instalar ou desinstalar e, para instalar, se há um arquivo da instalação para importar. */
@Component({
  selector: 'm2m-inicio',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatRadioModule,
    TranslocoPipe,
  ],
  templateUrl: './inicio.html',
  styleUrl: './inicio.scss',
})
export class Inicio {
  private api = inject(InstaladorApi);
  readonly quiz = inject(Quiz);
  readonly instalar = output();
  readonly desinstalar = output();

  readonly escolhaInstalar = signal(false);
  readonly temArquivo = signal<'nao' | 'sim'>('nao');
  readonly conteudo = signal('');
  readonly nomeArquivo = signal('');
  readonly senha = signal('');
  readonly erro = signal('');
  readonly importando = signal(false);
  /** Arquivo lido com a senha errada: o usuário escolhe tentar de novo ou seguir sem os segredos. */
  readonly semSegredos = signal<Importado | null>(null);

  async escolherArquivo(ev: Event): Promise<void> {
    const arquivo = (ev.target as HTMLInputElement).files?.[0];
    this.erro.set('');
    this.semSegredos.set(null);
    if (!arquivo) {
      return;
    }
    this.nomeArquivo.set(arquivo.name);
    this.conteudo.set(await arquivo.text());
  }

  comecar(): void {
    const e = this.quiz.estado();
    if (e) {
      this.quiz.iniciar(e);
    }
    this.instalar.emit();
  }

  async importar(): Promise<void> {
    this.erro.set('');
    this.semSegredos.set(null);
    this.importando.set(true);
    try {
      const imp = await this.api.importar(this.conteudo(), this.senha());
      if (imp.senhaErrada) {
        this.semSegredos.set(imp);
        return;
      }
      this.quiz.preencher(imp);
      this.instalar.emit();
    } catch (e) {
      this.erro.set(mensagemErro(e));
    } finally {
      this.importando.set(false);
    }
  }

  seguirSemSegredos(): void {
    const imp = this.semSegredos();
    if (imp) {
      this.quiz.preencher(imp);
      this.instalar.emit();
    }
  }
}
