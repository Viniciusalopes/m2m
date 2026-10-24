import { Component, effect, input, signal } from '@angular/core';
import { AbstractControl, ValidationErrors } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * Texto do erro de um campo: a mensagem do instalador (servidor) ou a do validador do formulário. Os erros vêm por
 * signal: os do instalador chegam depois de um await, fora de qualquer evento da própria etapa.
 */
@Component({
  selector: 'm2m-erro-campo',
  imports: [TranslocoPipe],
  template: `
    @let e = erros();
    @if (e) {
      @if (e['servidor']) {
        {{ e['servidor'] }}
      } @else if (e['required']) {
        {{ 'erro.obrigatorio' | transloco }}
      } @else if (e['minlength']) {
        {{ 'erro.minimo' | transloco: { n: e['minlength'].requiredLength } }}
      } @else if (e['min'] || e['max']) {
        {{ 'erro.faixa' | transloco: { min: minimo(), max: maximo() } }}
      } @else if (e['email']) {
        {{ 'erro.email' | transloco }}
      } @else if (e['pattern']) {
        {{ 'erro.formato' | transloco }}
      }
    }
  `,
})
export class ErroCampo {
  readonly controle = input.required<AbstractControl>();
  readonly minimo = input(1);
  readonly maximo = input(65535);
  readonly erros = signal<ValidationErrors | null>(null);

  constructor() {
    effect((limpar) => {
      const c = this.controle();
      this.erros.set(c.errors);
      const sub = c.events.subscribe(() => this.erros.set(c.errors));
      limpar(() => sub.unsubscribe());
    });
  }
}
