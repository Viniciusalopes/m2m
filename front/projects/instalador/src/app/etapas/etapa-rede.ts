import { Component, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { TranslocoPipe } from '@jsverse/transloco';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

/** Rede: endereço público (detectado ou digitado) e certificado. */
@Component({
  selector: 'm2m-etapa-rede',
  imports: [
    ReactiveFormsModule,
    MatAutocompleteModule,
    MatFormFieldModule,
    MatInputModule,
    MatRadioModule,
    TranslocoPipe,
    ErroCampo,
  ],
  templateUrl: './etapa-rede.html',
  styleUrl: './etapas.scss',
})
export class EtapaRede {
  readonly quiz = inject(Quiz);
  readonly form = this.quiz.rede;
}
