import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import { StatusChip, Estado } from '../ui/status-chip';
import { FidelityMatrix } from './fidelity-matrix';
import { Machine, MachinesService, TestResult, TestStep } from './machines.service';

@Component({
  selector: 'm2m-machine-test',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    StatusChip,
    FidelityMatrix,
    TranslocoPipe,
  ],
  templateUrl: './machine-test.html',
  styleUrl: './machine-test.scss',
})
export class MachineTest implements OnInit {
  private service = inject(MachinesService);
  readonly machine = inject<Machine>(MAT_DIALOG_DATA);

  readonly testando = signal(true);
  readonly resultado = signal<TestResult | null>(null);
  readonly erro = signal<ApiErro | null>(null);

  ngOnInit(): void {
    void this.executar();
  }

  async executar(): Promise<void> {
    this.testando.set(true);
    this.erro.set(null);
    try {
      this.resultado.set(await this.service.test(this.machine.id));
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.testando.set(false);
    }
  }

  estado(s: TestStep): Estado {
    return s.status === 'OK'
      ? 'ok'
      : s.status === 'FAIL'
        ? 'fail'
        : s.status === 'ACTION'
          ? 'warn'
          : 'info';
  }

  passoComAcao(): TestStep | undefined {
    return this.resultado()?.steps.find((s) => s.status === 'ACTION');
  }

  identidadeAlterada(): boolean {
    return this.passoComAcao()?.code === 'identidade.alterada';
  }

  async confirmar(): Promise<void> {
    const fp = this.resultado()?.fingerprint;
    if (!fp) {
      return;
    }
    try {
      await this.service.confirmIdentity(this.machine.id, fp);
      await this.executar();
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  detalhes(s: TestStep): Record<string, unknown> {
    return s.params ?? {};
  }
}
