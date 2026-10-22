import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { Fidelity } from './machines.service';

/** Matriz de fidelidade: o que o protocolo preserva (ícone + texto, nunca só cor). */
@Component({
  selector: 'm2m-fidelity',
  imports: [MatIconModule, TranslocoPipe],
  template: `
    <table class="matriz">
      <caption>
        {{
          'maquinas.fidelidade.titulo' | transloco
        }}
      </caption>
      <tbody>
        @for (linha of linhas(); track linha.chave) {
          <tr>
            <th scope="row">{{ 'maquinas.fidelidade.' + linha.chave | transloco }}</th>
            <td [class.sim]="linha.ok" [class.nao]="!linha.ok">
              <mat-icon aria-hidden="true">{{ linha.ok ? 'check_circle' : 'cancel' }}</mat-icon>
              {{
                (linha.ok ? 'maquinas.fidelidade.preservado' : 'maquinas.fidelidade.nao-preservado')
                  | transloco
              }}
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    .matriz {
      border-collapse: collapse;
      font: var(--mat-sys-body-medium);
    }
    caption {
      text-align: left;
      font: var(--mat-sys-label-large);
      padding-bottom: 4px;
    }
    th {
      text-align: left;
      font-weight: 500;
      padding: 2px 16px 2px 0;
    }
    td {
      padding: 2px 0;
    }
    td mat-icon {
      vertical-align: middle;
      font-size: 18px;
      width: 18px;
      height: 18px;
      margin-right: 4px;
    }
    .sim {
      color: var(--m2m-ok);
    }
    .nao {
      color: var(--m2m-warn);
    }
  `,
})
export class FidelityMatrix {
  readonly fidelidade = input.required<Fidelity>();

  linhas() {
    const f = this.fidelidade();
    return [
      { chave: 'content', ok: f.content },
      { chave: 'mtime', ok: f.mtime },
      { chave: 'permissions', ok: f.permissions },
      { chave: 'symlinks', ok: f.symlinks },
    ];
  }
}
