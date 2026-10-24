import { Component, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoPipe } from '@jsverse/transloco';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

/** Máquinas: só a máquina LOCAL; as remotas são cadastradas depois, na interface do m2m. */
@Component({
  selector: 'm2m-etapa-maquinas',
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    TranslocoPipe,
    ErroCampo,
  ],
  templateUrl: './etapa-maquinas.html',
  styleUrl: './etapas.scss',
})
export class EtapaMaquinas {
  readonly form = inject(Quiz).maquinas;
}
