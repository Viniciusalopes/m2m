import { Component, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoPipe } from '@jsverse/transloco';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

@Component({
  selector: 'm2m-etapa-logs',
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    TranslocoPipe,
    ErroCampo,
  ],
  templateUrl: './etapa-logs.html',
  styleUrl: './etapas.scss',
})
export class EtapaLogs {
  readonly form = inject(Quiz).logs;
}
