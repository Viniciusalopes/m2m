import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { APP_VERSION } from '../../core/version';

@Component({
  selector: 'm2m-about',
  imports: [MatCardModule, TranslocoPipe],
  templateUrl: './about.html',
  styleUrl: './about.scss',
})
export class About implements OnInit {
  private http = inject(HttpClient);
  readonly versao = APP_VERSION;
  readonly avisos = signal('');

  async ngOnInit(): Promise<void> {
    try {
      this.avisos.set(
        await firstValueFrom(
          this.http.get('assets/THIRD-PARTY-NOTICES.md', { responseType: 'text' }),
        ),
      );
    } catch {
      this.avisos.set('');
    }
  }
}
