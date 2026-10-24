import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { APP_VERSION } from '../../core/version';
import { FormaDoacao, formasDoacao } from './doacao';

@Component({
  selector: 'm2m-about',
  imports: [MatButtonModule, MatCardModule, MatIconModule, TranslocoPipe],
  templateUrl: './about.html',
  styleUrl: './about.scss',
})
export class About implements OnInit {
  private http = inject(HttpClient);
  readonly versao = APP_VERSION;
  readonly avisos = signal('');
  readonly doacoes = signal<FormaDoacao[]>([]);
  /** Nome da forma de doação copiada há pouco, para o rótulo "Copiado!". */
  readonly copiada = signal('');

  async ngOnInit(): Promise<void> {
    await Promise.all([this.carregarAvisos(), this.carregarDoacoes()]);
  }

  private async carregarAvisos(): Promise<void> {
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

  private async carregarDoacoes(): Promise<void> {
    try {
      this.doacoes.set(formasDoacao(await firstValueFrom(this.http.get('assets/doacao.json'))));
    } catch {
      this.doacoes.set([]);
    }
  }

  async copiar(forma: FormaDoacao): Promise<void> {
    await navigator.clipboard.writeText(forma.valor);
    this.copiada.set(forma.nome);
    setTimeout(() => this.copiada.set(''), 2500);
  }
}
