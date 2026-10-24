import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { Quiz } from '../quiz';
import { ErroCampo } from '../ui/erro-campo';

@Component({
  selector: 'm2m-etapa-usuarios',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    TranslocoPipe,
    ErroCampo,
  ],
  templateUrl: './etapa-usuarios.html',
  styleUrl: './etapas.scss',
})
export class EtapaUsuarios {
  readonly form = inject(Quiz).usuarios;
  readonly mostrar = signal(false);
}
