import { Component, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { Comando } from '../modelo';

/** Comando para o terminal: o que faz, para que serve e, por último, o texto com o botão de copiar. */
@Component({
  selector: 'm2m-cartao-comando',
  imports: [MatButtonModule, MatIconModule, TranslocoPipe],
  template: `
    @let c = comando();
    <section class="cartao">
      <h4>
        <mat-icon aria-hidden="true">{{
          c.privilegio ? 'admin_panel_settings' : 'terminal'
        }}</mat-icon>
        {{ c.titulo }}
      </h4>
      <p>
        <strong>{{ 'comando.o-que-faz' | transloco }}</strong> {{ c.oQueFaz }}
      </p>
      <p>
        <strong>{{ 'comando.para-que-serve' | transloco }}</strong> {{ c.paraQueServe }}
      </p>
      <div class="texto">
        <pre>{{ c.texto }}</pre>
        <button mat-stroked-button type="button" (click)="copiar()">
          <mat-icon>{{ copiado() ? 'check' : 'content_copy' }}</mat-icon>
          {{ (copiado() ? 'comando.copiado' : 'comando.copiar') | transloco }}
        </button>
      </div>
    </section>
  `,
  styles: `
    .cartao {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 0.75rem;
      padding: 0.75rem 1rem;
      margin: 0.75rem 0;
      background: var(--mat-sys-surface-container-low);
    }
    h4 {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0 0 0.5rem;
      font: var(--mat-sys-title-small);
    }
    p {
      margin: 0.25rem 0;
    }
    .texto {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      margin-top: 0.75rem;
    }
    pre {
      flex: 1;
      margin: 0;
      padding: 0.75rem;
      overflow-x: auto;
      border-radius: 0.5rem;
      background: var(--mat-sys-surface-container-highest);
      font-size: 0.85rem;
    }
  `,
})
export class CartaoComando {
  readonly comando = input.required<Comando>();
  readonly copiado = signal(false);

  async copiar(): Promise<void> {
    await navigator.clipboard.writeText(this.comando().texto);
    this.copiado.set(true);
    setTimeout(() => this.copiado.set(false), 2000);
  }
}
