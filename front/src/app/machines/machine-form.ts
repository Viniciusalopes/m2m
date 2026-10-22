import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro, ApiErro } from '../core/api-error';
import {
  Machine,
  MachineRequest,
  MachinesService,
  Protocol,
  SshIdentity,
} from './machines.service';

export interface MachineFormData {
  machine: Machine | null;
  /** Há máquina LOCAL cadastrada ou o acesso LOCAL está desativado: esconde essa opção. */
  localDisponivel: boolean;
}

@Component({
  selector: 'm2m-machine-form',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    TranslocoPipe,
  ],
  templateUrl: './machine-form.html',
  styleUrl: './machine-form.scss',
})
export class MachineForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(MachinesService);
  private ref = inject(MatDialogRef<MachineForm, Machine>);
  readonly data = inject<MachineFormData>(MAT_DIALOG_DATA);

  readonly editando = this.data.machine !== null;
  readonly protocolos: Protocol[] = this.data.localDisponivel
    ? ['SFTP', 'FTP', 'FTPS', 'LOCAL']
    : ['SFTP', 'FTP', 'FTPS'];
  readonly erro = signal<ApiErro | null>(null);
  readonly salvando = signal(false);
  readonly identidade = signal<SshIdentity | null>(null);
  readonly copiado = signal(false);

  readonly form = this.fb.nonNullable.group({
    name: [this.data.machine?.name ?? '', [Validators.required, Validators.maxLength(100)]],
    description: [this.data.machine?.description ?? ''],
    protocol: [(this.data.machine?.protocol ?? 'SFTP') as Protocol],
    host: [this.data.machine?.host ?? ''],
    port: [this.data.machine?.port ?? 22],
    ftpsMode: [this.data.machine?.ftpsMode ?? 'EXPLICIT'],
    username: [this.data.machine?.username ?? ''],
    authType: [this.data.machine?.authType ?? 'KEY'],
    password: [''],
  });

  ngOnInit(): void {
    this.form.controls.protocol.valueChanges.subscribe(() => this.ajustarPorta());
    this.form.controls.ftpsMode.valueChanges.subscribe(() => this.ajustarPorta());
    this.form.controls.authType.valueChanges.subscribe(() => void this.carregarChave());
    void this.carregarChave();
    if (this.editando) {
      this.form.controls.protocol.disable();
    }
  }

  protocolo(): Protocol {
    return this.form.controls.protocol.value;
  }

  usaChave(): boolean {
    return this.protocolo() === 'SFTP' && this.form.controls.authType.value === 'KEY';
  }

  precisaSenha(): boolean {
    return this.protocolo() !== 'LOCAL' && !this.usaChave();
  }

  senhaSalva(): boolean {
    return this.editando && !!this.data.machine?.hasPassword;
  }

  private ajustarPorta(): void {
    if (this.editando) {
      return;
    }
    const p = this.protocolo();
    const porta =
      p === 'SFTP'
        ? 22
        : p === 'FTP'
          ? 21
          : p === 'FTPS'
            ? this.form.controls.ftpsMode.value === 'IMPLICIT'
              ? 990
              : 21
            : 0;
    this.form.controls.port.setValue(porta);
  }

  private async carregarChave(): Promise<void> {
    if (this.usaChave() && !this.identidade()) {
      this.identidade.set(await this.service.sshIdentity());
    }
  }

  async copiar(texto: string): Promise<void> {
    await navigator.clipboard.writeText(texto);
    this.copiado.set(true);
    setTimeout(() => this.copiado.set(false), 2500);
  }

  async salvar(): Promise<void> {
    if (this.form.invalid || this.salvando()) {
      return;
    }
    this.salvando.set(true);
    this.erro.set(null);
    const v = this.form.getRawValue();
    const local = v.protocol === 'LOCAL';
    const req: MachineRequest = {
      name: v.name.trim(),
      description: v.description.trim() || null,
      protocol: v.protocol,
      host: local ? null : v.host.trim(),
      port: local ? null : v.port,
      ftpsMode: v.protocol === 'FTPS' ? v.ftpsMode : null,
      username: local ? null : v.username.trim(),
      authType: v.protocol === 'SFTP' ? v.authType : null,
      password: this.precisaSenha() ? v.password : null,
    };
    try {
      const salva = this.editando
        ? await this.service.update(this.data.machine!.id, req)
        : await this.service.create(req);
      this.ref.close(salva);
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.salvando.set(false);
    }
  }
}
