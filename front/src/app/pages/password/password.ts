import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { apiErro, ApiErro } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';

function confere(group: AbstractControl): ValidationErrors | null {
  const nova = group.get('newPassword')?.value;
  const conf = group.get('confirm')?.value;
  return nova && conf && nova !== conf ? { diferente: true } : null;
}

@Component({
  selector: 'm2m-password',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    TranslocoPipe,
  ],
  templateUrl: './password.html',
  styleUrl: './password.scss',
})
export class Password {
  private http = inject(HttpClient);
  private fb = inject(FormBuilder);
  readonly auth = inject(AuthService);

  readonly senhaForm = this.fb.nonNullable.group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(10)]],
      confirm: ['', Validators.required],
    },
    { validators: confere },
  );
  readonly emailForm = this.fb.nonNullable.group({
    email: [this.auth.me()?.email ?? '', [Validators.required, Validators.email]],
  });

  readonly senhaOk = signal(false);
  readonly emailOk = signal(false);
  readonly erro = signal<ApiErro | null>(null);

  async trocarSenha(): Promise<void> {
    if (this.senhaForm.invalid) {
      return;
    }
    this.erro.set(null);
    this.senhaOk.set(false);
    const { currentPassword, newPassword } = this.senhaForm.getRawValue();
    try {
      await firstValueFrom(this.http.put('/api/me/password', { currentPassword, newPassword }));
      this.senhaForm.reset();
      this.senhaOk.set(true);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }

  async trocarEmail(): Promise<void> {
    if (this.emailForm.invalid) {
      return;
    }
    this.erro.set(null);
    this.emailOk.set(false);
    try {
      await firstValueFrom(this.http.put('/api/me/email', this.emailForm.getRawValue()));
      await this.auth.restore();
      this.emailOk.set(true);
    } catch (e) {
      this.erro.set(apiErro(e));
    }
  }
}
