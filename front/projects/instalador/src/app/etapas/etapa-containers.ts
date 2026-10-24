import { Component, computed, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

@Component({
  selector: 'm2m-etapa-containers',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, TranslocoPipe, ErroCampo],
  templateUrl: './etapa-containers.html',
  styleUrl: './etapas.scss',
})
export class EtapaContainers {
  private readonly quiz = inject(Quiz);
  readonly form = this.quiz.containers;
  /** Modo clone: o diretório é o próprio clone e não pode ser trocado. */
  readonly clone = computed(() => this.quiz.estado()?.clone ?? '');
}
