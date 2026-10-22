import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { BytesPipe } from '../ui/bytes.pipe';
import { Backup, BackupsService, TreePage } from './backups.service';

/** "Ver conteúdo": navega pela árvore do backup a partir do manifesto lateral, sem descompactar o arquivo. */
@Component({
  selector: 'm2m-backup-tree',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    TranslocoPipe,
    DatePipe,
    BytesPipe,
  ],
  templateUrl: './backup-tree.html',
  styleUrl: './backup-tree.scss',
})
export class BackupTree implements OnInit {
  private service = inject(BackupsService);
  readonly backup = inject<Backup>(MAT_DIALOG_DATA);

  readonly pagina = signal<TreePage | null>(null);
  readonly carregando = signal(false);
  readonly erro = signal<ApiErro | null>(null);
  readonly itens = signal<TreePage['items']>([]);
  private proxima = 0;

  ngOnInit(): void {
    void this.abrir('');
  }

  async abrir(path: string): Promise<void> {
    this.proxima = 0;
    this.itens.set([]);
    await this.carregar(path);
  }

  async carregar(path: string): Promise<void> {
    this.carregando.set(true);
    this.erro.set(null);
    try {
      const p = await this.service.tree(this.backup.id, path, this.proxima);
      this.pagina.set(p);
      this.itens.update((l) => [...l, ...p.items]);
      this.proxima++;
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.carregando.set(false);
    }
  }

  temMais(): boolean {
    const p = this.pagina();
    return !!p && this.itens().length < p.total;
  }

  async maisItens(): Promise<void> {
    const p = this.pagina();
    if (p) {
      await this.carregar(p.path);
    }
  }

  icone(tipo: string): string {
    return tipo === 'DIR' ? 'folder' : tipo === 'SYMLINK' ? 'link' : 'description';
  }
}
