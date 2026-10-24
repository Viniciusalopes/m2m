import { Component, inject, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { Etapa, Quiz } from '../quiz';

/** Mascara um segredo, mostrando só que existe e o tamanho aproximado. */
export function mascarar(s: string): string {
  return s ? '•'.repeat(Math.min(s.length, 12)) : '—';
}

/** Revisão: resumo de todas as etapas, com os segredos mascarados e o caminho de volta para corrigir. */
@Component({
  selector: 'm2m-etapa-revisao',
  imports: [MatButtonModule, MatIconModule, TranslocoPipe],
  templateUrl: './etapa-revisao.html',
  styleUrl: './etapa-revisao.scss',
})
export class EtapaRevisao {
  readonly quiz = inject(Quiz);
  readonly corrigir = output<Etapa>();
  readonly mascarar = mascarar;
}
