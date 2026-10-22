import { Component, inject, OnInit, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { JobsService } from './jobs.service';

/** "Acompanhamento": abre o job em execução (ou o primeiro da fila); sem job ativo, avisa. */
@Component({
  selector: 'm2m-jobs-redirect',
  imports: [MatCardModule, RouterLink, TranslocoPipe],
  template: `
    <section class="m2m-page">
      <h1>{{ 'jobs.titulo' | transloco }}</h1>
      @if (vazio()) {
        <mat-card appearance="outlined">
          <mat-card-content>
            <p>{{ 'jobs.nenhum-ativo' | transloco }}</p>
            <a routerLink="/perfis">{{ 'jobs.ir-perfis' | transloco }}</a>
          </mat-card-content>
        </mat-card>
      }
    </section>
  `,
})
export class JobsRedirect implements OnInit {
  private jobs = inject(JobsService);
  private router = inject(Router);
  readonly vazio = signal(false);

  async ngOnInit(): Promise<void> {
    const a = await this.jobs.active();
    const alvo = a.running ?? a.queued[0];
    if (alvo) {
      await this.router.navigate(['/jobs', alvo.id], { replaceUrl: true });
    } else {
      this.vazio.set(true);
    }
  }
}
