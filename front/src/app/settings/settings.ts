import { Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { SmtpSection } from './smtp-section';

/** Configurações do usuário; as demais seções (verificação periódica, backup do banco, exportação, 2FA) chegam nos próximos marcos. */
@Component({
  selector: 'm2m-settings',
  imports: [SmtpSection, TranslocoPipe],
  template: `
    <section class="m2m-page config">
      <h1>{{ 'config.titulo' | transloco }}</h1>
      <m2m-smtp-section />
    </section>
  `,
  styles: `
    .config {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
  `,
})
export class Settings {}
