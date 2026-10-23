import { DecimalPipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { Machine, MachinesService } from '../machines/machines.service';
import { SyncPreview, SyncRequest, SyncService } from '../sync/sync.service';
import { Profile } from './profiles.service';

/** "Sincronizar agora": escolhe o destino, mostra a prévia e pede as confirmações exigidas (D24). */
@Component({
  selector: 'm2m-sync-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatSelectModule,
    DecimalPipe,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'sincronizacao.titulo' | transloco: { nome: data.name } }}</h2>
    <mat-dialog-content>
      <p>{{ 'sincronizacao.explicacao' | transloco }}</p>
      @if (carregado() && !destinos().length) {
        <p class="aviso">{{ 'sincronizacao.sem-destinos' | transloco }}</p>
      }
      <form [formGroup]="form">
        <mat-form-field appearance="outline" class="campo">
          <mat-label>{{ 'sincronizacao.destino' | transloco }}</mat-label>
          <mat-select formControlName="destMachineId" (selectionChange)="limpar()">
            @for (d of destinos(); track d.id) {
              <mat-option [value]="d.id">{{ d.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-checkbox formControlName="compareContent" (change)="limpar()">{{
          'sincronizacao.comparar-conteudo' | transloco
        }}</mat-checkbox>
      </form>
      <button
        mat-stroked-button
        type="button"
        (click)="previa()"
        [disabled]="form.invalid || carregando()"
      >
        {{ 'sincronizacao.previsualizar' | transloco }}
      </button>

      @if (erro(); as e) {
        <p class="aviso" role="alert">{{ 'erro.' + e.codigo | transloco: e.parametros }}</p>
      }

      @if (preview(); as p) {
        @if (p.alreadySynced) {
          <p class="ok">{{ 'sincronizacao.ja-sincronizado' | transloco }}</p>
        } @else {
          <ul class="resumo">
            <li>
              {{ 'sincronizacao.criar' | transloco }}: {{ p.create | number: '1.0-0' : 'pt-BR' }}
            </li>
            <li>
              {{ 'sincronizacao.atualizar' | transloco }}:
              {{ p.update | number: '1.0-0' : 'pt-BR' }}
            </li>
            <li class="exclusao">
              {{ 'sincronizacao.excluir' | transloco }}: {{ p.delete | number: '1.0-0' : 'pt-BR' }}
            </li>
          </ul>
        }
        @for (w of p.warnings; track w) {
          <p class="aviso">{{ 'sincronizacao.alerta.' + w | transloco }}</p>
        }
        @if (p.emptySourceDeletes > 0) {
          <p class="aviso">
            {{ 'sincronizacao.origem-vazia' | transloco: { itens: p.emptySourceDeletes } }}
          </p>
          <mat-checkbox [checked]="confirmaVazia()" (change)="confirmaVazia.set($event.checked)">{{
            'sincronizacao.confirmar-origem-vazia' | transloco
          }}</mat-checkbox>
        }
        @if (p.destinationChanges > 0) {
          <p class="aviso">
            {{ 'sincronizacao.trabalho-no-destino' | transloco: { itens: p.destinationChanges } }}
          </p>
          <ul class="amostra">
            @for (a of p.destinationSample; track a) {
              <li>{{ a }}</li>
            }
          </ul>
          <mat-checkbox
            [checked]="confirmaDestino()"
            (change)="confirmaDestino.set($event.checked)"
            >{{ 'sincronizacao.confirmar-trabalho-no-destino' | transloco }}</mat-checkbox
          >
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-flat-button type="button" (click)="iniciar()" [disabled]="!podeIniciar()">
        {{ 'sincronizacao.iniciar' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .campo {
      width: 100%;
    }
    .aviso {
      color: var(--m2m-warn);
    }
    .ok {
      color: var(--m2m-ok);
    }
    .exclusao {
      color: var(--m2m-warn);
      font-weight: 600;
    }
    .amostra {
      max-height: 7.5rem;
      overflow: auto;
      font-family: monospace;
    }
  `,
})
export class SyncDialog implements OnInit {
  readonly data = inject<Profile>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<SyncDialog, SyncRequest>);
  private machines = inject(MachinesService);
  private sync = inject(SyncService);

  readonly destinos = signal<Machine[]>([]);
  readonly carregado = signal(false);
  readonly carregando = signal(false);
  readonly preview = signal<SyncPreview | null>(null);
  readonly erro = signal<ApiErro | null>(null);
  readonly confirmaVazia = signal(false);
  readonly confirmaDestino = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    destMachineId: [0, Validators.min(1)],
    compareContent: [false],
  });

  async ngOnInit(): Promise<void> {
    const todas = await this.machines.list();
    // só máquinas com mapeamento em todos os diretórios, diferentes da origem
    const comMapeamento = todas.filter(
      (m) =>
        m.id !== this.data.sourceMachineId &&
        this.data.directories.length > 0 &&
        this.data.directories.every((d) => d.mappings.some((x) => x.machineId === m.id)),
    );
    this.destinos.set(comMapeamento);
    this.carregado.set(true);
    if (comMapeamento.length) {
      this.form.controls.destMachineId.setValue(comMapeamento[0].id);
    }
  }

  limpar(): void {
    this.preview.set(null);
    this.erro.set(null);
    this.confirmaVazia.set(false);
    this.confirmaDestino.set(false);
  }

  private pedido(): SyncRequest {
    return {
      ...this.form.getRawValue(),
      confirmed: true,
      confirmEmptySource: this.confirmaVazia(),
      confirmDestinationChanges: this.confirmaDestino(),
    };
  }

  async previa(): Promise<void> {
    this.limpar();
    this.carregando.set(true);
    try {
      this.preview.set(await this.sync.preview(this.data.id, this.pedido()));
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.carregando.set(false);
    }
  }

  podeIniciar(): boolean {
    const p = this.preview();
    if (!p || p.alreadySynced) {
      return false;
    }
    return (
      (p.emptySourceDeletes === 0 || this.confirmaVazia()) &&
      (p.destinationChanges === 0 || this.confirmaDestino())
    );
  }

  iniciar(): void {
    if (this.podeIniciar()) {
      this.ref.close(this.pedido());
    }
  }
}
