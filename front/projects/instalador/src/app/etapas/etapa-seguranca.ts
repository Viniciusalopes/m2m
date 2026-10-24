import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoPipe } from '@jsverse/transloco';
import { salvarArquivo } from '../api';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

/** Segurança: chave mestra gerada ou informada e confirmação de que o usuário a guardou. */
@Component({
  selector: 'm2m-etapa-seguranca',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    TranslocoPipe,
    ErroCampo,
  ],
  templateUrl: './etapa-seguranca.html',
  styleUrl: './etapas.scss',
})
export class EtapaSeguranca {
  readonly quiz = inject(Quiz);
  readonly form = this.quiz.seguranca;
  readonly copiada = signal(false);
  readonly mostrar = signal(false);

  /** Trocar entre gerada e informada limpa o campo ou devolve a chave gerada. */
  alternar(informada: boolean): void {
    const gerada = this.quiz.estado()?.segredos.chaveMestra ?? '';
    this.form.controls.chaveMestra.setValue(informada ? '' : gerada);
    this.form.controls.guardei.setValue(false);
  }

  async copiar(): Promise<void> {
    await navigator.clipboard.writeText(this.form.controls.chaveMestra.value);
    this.copiada.set(true);
    setTimeout(() => this.copiada.set(false), 2000);
  }

  baixar(): void {
    const texto = `# Chave mestra do m2m (APP_SECRET_KEY). Guarde num cofre de senhas.\nAPP_SECRET_KEY=${this.form.controls.chaveMestra.value}\n`;
    salvarArquivo(new Blob([texto], { type: 'text/plain' }), 'm2m-chave-mestra.txt');
  }
}
