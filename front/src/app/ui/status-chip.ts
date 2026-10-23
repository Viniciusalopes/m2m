import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';

export type Estado = 'ok' | 'warn' | 'suspect' | 'fail' | 'corrupt' | 'info' | 'running';

const ICONES: Record<Estado, string> = {
  ok: 'check_circle',
  warn: 'warning',
  suspect: 'help',
  fail: 'error',
  corrupt: 'broken_image',
  info: 'info',
  running: 'sync',
};

/** Estado do sistema: sempre cor + ícone + texto, nunca só cor (D34). */
@Component({
  selector: 'm2m-status',
  imports: [MatIconModule, TranslocoPipe],
  template: `
    <span class="chip" [class]="'chip ' + estado()">
      <mat-icon aria-hidden="true">{{ icone() }}</mat-icon>
      <span>{{ texto() ?? ('estado.' + estado() | transloco) }}</span>
    </span>
  `,
  styles: `
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 2px 0.625rem 2px 0.375rem;
      border-radius: 62.4375rem;
      font: var(--mat-sys-label-large);
      white-space: nowrap;
    }
    .chip mat-icon {
      font-size: 1.125rem;
      width: 1.125rem;
      height: 1.125rem;
    }
    .ok {
      color: var(--m2m-ok);
      background: var(--m2m-ok-container);
    }
    .warn {
      color: var(--m2m-warn);
      background: var(--m2m-warn-container);
    }
    .suspect {
      color: var(--m2m-suspect);
      background: var(--m2m-suspect-container);
    }
    .fail {
      color: var(--m2m-fail);
      background: var(--m2m-fail-container);
    }
    .corrupt {
      color: var(--m2m-corrupt);
      background: var(--m2m-corrupt-container);
    }
    .info,
    .running {
      color: var(--m2m-info);
      background: var(--m2m-info-container);
    }
  `,
})
export class StatusChip {
  readonly estado = input.required<Estado>();
  /** Texto já traduzido; sem ele, usa `estado.<estado>`. */
  readonly texto = input<string>();
  readonly icone = computed(() => ICONES[this.estado()]);
}
