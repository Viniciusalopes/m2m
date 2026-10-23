import { Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ConfigTransferSection } from './config-transfer-section';
import { MaintenanceSection } from './maintenance-section';
import { SmtpSection } from './smtp-section';
import { TotpSection } from './totp-section';

/** Configurações do usuário;. */
@Component({
  selector: 'm2m-settings',
  imports: [SmtpSection, TotpSection, MaintenanceSection, ConfigTransferSection, TranslocoPipe],
  template: `
    <section class="m2m-page config">
      <h1>{{ 'config.titulo' | transloco }}</h1>
      <m2m-smtp-section />
      <m2m-totp-section />
      <m2m-maintenance-section />
      <m2m-config-transfer-section />
    </section>
  `,
  styles: `
    .config {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
  `,
})
export class Settings {}
