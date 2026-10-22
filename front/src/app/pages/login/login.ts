import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { apiErro } from '../../core/api-error';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'm2m-login',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    TranslocoPipe,
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  readonly expirada = inject(ActivatedRoute).snapshot.queryParamMap.has('expirada');

  readonly form = this.fb.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });
  readonly enviando = signal(false);
  readonly erro = signal<{ codigo: string; parametros?: Record<string, unknown> } | null>(null);
  readonly mostrarSenha = signal(false);

  async entrar(): Promise<void> {
    if (this.form.invalid || this.enviando()) {
      return;
    }
    this.enviando.set(true);
    this.erro.set(null);
    try {
      const { username, password } = this.form.getRawValue();
      await this.auth.login(username, password);
      await this.router.navigate(['/']);
    } catch (e) {
      this.erro.set(apiErro(e));
    } finally {
      this.enviando.set(false);
    }
  }
}
