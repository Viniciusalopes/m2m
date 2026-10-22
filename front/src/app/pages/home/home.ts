import { Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../../core/auth.service';

/** Painel inicial: o conteúdo (perfis, jobs, alertas) chega com a tarefa 11.4. */
@Component({
  selector: 'm2m-home',
  imports: [MatCardModule, TranslocoPipe],
  template: `
    <section class="m2m-page">
      <h1>{{ 'home.titulo' | transloco }}</h1>
      <mat-card appearance="outlined">
        <mat-card-content>
          <p>{{ 'home.boas-vindas' | transloco: { usuario: auth.me()?.username } }}</p>
        </mat-card-content>
      </mat-card>
    </section>
  `,
})
export class Home {
  readonly auth = inject(AuthService);
}
