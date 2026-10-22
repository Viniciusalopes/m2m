import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { StatusChip } from '../ui/status-chip';

interface DirReport {
  source: string;
  target: string;
  identical: boolean;
  onlySourceCount: number;
  onlyTargetCount: number;
  differentCount: number;
  filesCompared: number;
  onlySource: string[];
  onlyTarget: string[];
  different: { path: string; reasons: string[] }[];
  truncated: boolean;
}

export interface VerifyResult {
  mode: 'QUICK' | 'FULL';
  identical: boolean;
  filesCompared: number;
  sourceMachine: string;
  targetMachine: string;
  directories: DirReport[];
  notCompared: string[];
  specialsIgnored: number;
}

/** Relatório da verificação: "Idênticos" ou as diferenças por diretório (só na origem, só no destino, diferentes). */
@Component({
  selector: 'm2m-verify-report',
  imports: [MatIconModule, StatusChip, TranslocoPipe],
  template: `
    @if (resultado(); as r) {
      <section class="relatorio">
        <h3>
          <m2m-status
            [estado]="r.identical ? 'ok' : 'fail'"
            [texto]="(r.identical ? 'verificacao.identicos' : 'verificacao.diferentes') | transloco"
          />
          {{
            'verificacao.resumo'
              | transloco
                : {
                    origem: r.sourceMachine,
                    destino: r.targetMachine,
                    modo: ('verificacao.modo.' + r.mode | transloco),
                  }
          }}
        </h3>
        <p>{{ 'verificacao.arquivos-comparados' | transloco: { total: r.filesCompared } }}</p>
        @for (n of r.notCompared; track n) {
          <p class="aviso">
            <mat-icon aria-hidden="true">info</mat-icon>
            {{ 'verificacao.nao-comparado.' + n | transloco }}
          </p>
        }
        @if (r.specialsIgnored) {
          <p class="aviso">
            <mat-icon aria-hidden="true">info</mat-icon>
            {{ 'verificacao.especiais' | transloco: { total: r.specialsIgnored } }}
          </p>
        }
        @for (d of r.directories; track d.source) {
          <div class="diretorio">
            <h4>
              {{ d.source }} → {{ d.target }}
              <m2m-status
                [estado]="d.identical ? 'ok' : 'fail'"
                [texto]="
                  (d.identical ? 'verificacao.identicos' : 'verificacao.diferentes') | transloco
                "
              />
            </h4>
            @if (d.onlySource.length) {
              <h5>{{ 'verificacao.so-na-origem' | transloco: { total: d.onlySourceCount } }}</h5>
              <ul>
                @for (p of d.onlySource; track p) {
                  <li>{{ p }}</li>
                }
              </ul>
            }
            @if (d.onlyTarget.length) {
              <h5>{{ 'verificacao.so-no-destino' | transloco: { total: d.onlyTargetCount } }}</h5>
              <ul>
                @for (p of d.onlyTarget; track p) {
                  <li>{{ p }}</li>
                }
              </ul>
            }
            @if (d.different.length) {
              <h5>{{ 'verificacao.com-diferenca' | transloco: { total: d.differentCount } }}</h5>
              <ul>
                @for (x of d.different; track x.path) {
                  <li>
                    {{ x.path }} —
                    @for (m of x.reasons; track m; let last = $last) {
                      {{ 'verificacao.motivo.' + m | transloco }}{{ last ? '' : ', ' }}
                    }
                  </li>
                }
              </ul>
            }
            @if (d.truncated) {
              <p class="aviso">{{ 'verificacao.truncado' | transloco }}</p>
            }
          </div>
        }
      </section>
    }
  `,
  styles: `
    .relatorio h3,
    .relatorio h4 {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    h5 {
      margin: 12px 0 4px;
    }
    ul {
      margin: 0;
      padding-left: 20px;
      font-family: ui-monospace, monospace;
      font-size: 0.85rem;
      word-break: break-all;
    }
    .aviso {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--m2m-warn);
    }
    .diretorio {
      border-top: 1px solid var(--mat-sys-outline-variant);
      padding-top: 8px;
      margin-top: 8px;
    }
  `,
})
export class VerifyReport {
  readonly result = input<Record<string, unknown> | null>(null);
  readonly resultado = computed(() => {
    const r = this.result();
    return r && 'directories' in r ? (r as unknown as VerifyResult) : null;
  });
}
