import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import {
  FilterCondition,
  FilterDefinition,
  SavedFilter,
  TransferService,
} from './transfer.service';

const OPERADORES: Record<string, string[]> = {
  NAME: ['CONTAINS', 'EQUALS', 'STARTS', 'ENDS', 'REGEX'],
  PATH: ['CONTAINS', 'EQUALS', 'STARTS', 'ENDS', 'REGEX'],
  SIZE: ['EQ', 'NE', 'GT', 'LT'],
  MTIME: ['BEFORE', 'AFTER'],
  PERMISSIONS: ['EQUALS', 'HAS'],
};

/** Editor de filtro (D22): condições combinadas por todas/qualquer/nenhuma, com conjuntos salvos. */
@Component({
  selector: 'm2m-filter-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatRadioModule,
    MatSelectModule,
    TranslocoPipe,
  ],
  template: `
    <h2 mat-dialog-title>{{ 'transferencia.filtro.titulo' | transloco }}</h2>
    <mat-dialog-content>
      <p>{{ 'transferencia.filtro.explicacao' | transloco }}</p>
      <mat-radio-group [ngModel]="match()" (ngModelChange)="match.set($event)" class="combinacao">
        <mat-radio-button value="ALL">{{
          'transferencia.filtro.todas' | transloco
        }}</mat-radio-button>
        <mat-radio-button value="ANY">{{
          'transferencia.filtro.qualquer' | transloco
        }}</mat-radio-button>
        <mat-radio-button value="NONE">{{
          'transferencia.filtro.nenhuma' | transloco
        }}</mat-radio-button>
      </mat-radio-group>
      <mat-checkbox [ngModel]="caseSensitive()" (ngModelChange)="caseSensitive.set($event)">{{
        'transferencia.filtro.maiusculas' | transloco
      }}</mat-checkbox>

      @for (c of condicoes(); track $index; let i = $index) {
        <div class="condicao">
          <mat-form-field appearance="outline">
            <mat-label>{{ 'transferencia.filtro.campo' | transloco }}</mat-label>
            <mat-select [ngModel]="c.field" (ngModelChange)="campo(i, $event)">
              @for (f of campos; track f) {
                <mat-option [value]="f">{{
                  'transferencia.filtro.campos.' + f | transloco
                }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>{{ 'transferencia.filtro.operador' | transloco }}</mat-label>
            <mat-select [ngModel]="c.op" (ngModelChange)="atualizar(i, { op: $event })">
              @for (o of operadores(c.field); track o) {
                <mat-option [value]="o">{{
                  'transferencia.filtro.ops.' + o | transloco
                }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="valor">
            <mat-label>{{ 'transferencia.filtro.valor' | transloco }}</mat-label>
            <input matInput [ngModel]="c.value" (ngModelChange)="atualizar(i, { value: $event })" />
          </mat-form-field>
          <button
            mat-icon-button
            type="button"
            (click)="remover(i)"
            [attr.aria-label]="'comum.excluir' | transloco"
          >
            <mat-icon>delete</mat-icon>
          </button>
        </div>
      }
      <button mat-stroked-button type="button" (click)="adicionar()">
        <mat-icon>add</mat-icon>{{ 'transferencia.filtro.adicionar' | transloco }}
      </button>

      <h3>{{ 'transferencia.filtro.conjuntos' | transloco }}</h3>
      <div class="conjuntos">
        <mat-form-field appearance="outline">
          <mat-label>{{ 'transferencia.filtro.salvos' | transloco }}</mat-label>
          <mat-select [ngModel]="selecionado()" (ngModelChange)="selecionado.set($event)">
            @for (s of salvos(); track s.id) {
              <mat-option [value]="s.id">{{ s.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <button mat-button type="button" (click)="carregar()" [disabled]="!selecionado()">
          {{ 'transferencia.filtro.carregar' | transloco }}
        </button>
        <button mat-button type="button" (click)="excluirSalvo()" [disabled]="!selecionado()">
          {{ 'comum.excluir' | transloco }}
        </button>
      </div>
      <div class="conjuntos">
        <mat-form-field appearance="outline">
          <mat-label>{{ 'transferencia.filtro.nome-conjunto' | transloco }}</mat-label>
          <input matInput [ngModel]="nome()" (ngModelChange)="nome.set($event)" />
        </mat-form-field>
        <button
          mat-button
          type="button"
          (click)="salvar()"
          [disabled]="!nome().trim() || !condicoes().length"
        >
          {{ 'transferencia.filtro.salvar-conjunto' | transloco }}
        </button>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'comum.cancelar' | transloco }}</button>
      <button mat-button type="button" (click)="limpar()">
        {{ 'transferencia.filtro.limpar' | transloco }}
      </button>
      <button mat-flat-button type="button" (click)="aplicar()">
        {{ 'transferencia.filtro.aplicar' | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .combinacao {
      display: flex;
      flex-direction: column;
      margin-bottom: 0.5rem;
    }
    .condicao,
    .conjuntos {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      flex-wrap: wrap;
    }
    .valor {
      flex: 1 1 10rem;
    }
  `,
})
export class FilterDialog implements OnInit {
  readonly data = inject<{ definition: FilterDefinition | null }>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<FilterDialog, FilterDefinition | null>);
  private service = inject(TransferService);

  readonly campos = Object.keys(OPERADORES);
  readonly match = signal<'ALL' | 'ANY' | 'NONE'>('ANY');
  readonly caseSensitive = signal(false);
  readonly condicoes = signal<FilterCondition[]>([]);
  readonly salvos = signal<SavedFilter[]>([]);
  readonly selecionado = signal<number | null>(null);
  readonly nome = signal('');

  async ngOnInit(): Promise<void> {
    const d = this.data.definition;
    if (d) {
      this.match.set(d.match);
      this.caseSensitive.set(d.caseSensitive);
      this.condicoes.set(d.conditions.map((c) => ({ ...c })));
    }
    this.salvos.set(await this.service.filters());
  }

  operadores(campo: string): string[] {
    return OPERADORES[campo] ?? [];
  }

  adicionar(): void {
    this.condicoes.update((l) => [...l, { field: 'NAME', op: 'CONTAINS', value: '' }]);
  }

  remover(i: number): void {
    this.condicoes.update((l) => l.filter((_, k) => k !== i));
  }

  atualizar(i: number, parcial: Partial<FilterCondition>): void {
    this.condicoes.update((l) => l.map((c, k) => (k === i ? { ...c, ...parcial } : c)));
  }

  campo(i: number, campo: string): void {
    this.atualizar(i, { field: campo, op: OPERADORES[campo][0] });
  }

  private definicao(): FilterDefinition {
    return {
      match: this.match(),
      caseSensitive: this.caseSensitive(),
      conditions: this.condicoes(),
    };
  }

  aplicar(): void {
    this.ref.close(this.condicoes().length ? this.definicao() : null);
  }

  limpar(): void {
    this.ref.close(null);
  }

  carregar(): void {
    const s = this.salvos().find((x) => x.id === this.selecionado());
    if (s) {
      this.match.set(s.definition.match);
      this.caseSensitive.set(s.definition.caseSensitive);
      this.condicoes.set(s.definition.conditions.map((c) => ({ ...c })));
    }
  }

  async salvar(): Promise<void> {
    await this.service.saveFilter(this.nome().trim(), this.definicao());
    this.salvos.set(await this.service.filters());
    this.nome.set('');
  }

  async excluirSalvo(): Promise<void> {
    const id = this.selecionado();
    if (id) {
      await this.service.deleteFilter(id);
      this.selecionado.set(null);
      this.salvos.set(await this.service.filters());
    }
  }
}
