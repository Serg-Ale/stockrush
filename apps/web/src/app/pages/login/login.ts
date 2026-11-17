import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';

/**
 * The generated client rejects with the response body, which for this api is a Problem Details document carrying
 * the HTTP status. A transport-level HttpErrorResponse has it directly.
 */
function statusOf(error: unknown): number | null {
  if (error instanceof HttpErrorResponse) return error.status;
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === 'number' ? status : null;
}

/** Maps a failed login or sign-up to the message the user sees. */
export function errorMessage(error: unknown, creating: boolean): string {
  switch (statusOf(error)) {
    case 401:
      return 'E-mail ou senha inválidos.';
    case 409:
      return 'Este e-mail já está cadastrado.';
    case 400:
      return 'Confira o e-mail e use uma senha de 8 a 72 caracteres.';
    case 429:
      return 'Muitas tentativas. Aguarde um instante.';
    default:
      return creating
        ? 'Não foi possível criar a conta. Tente novamente.'
        : 'Não foi possível entrar. Tente novamente.';
  }
}

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly creating = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
  });

  protected toggleMode(): void {
    this.creating.update((value) => !value);
    this.error.set(null);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid || this.busy()) return;
    const { email, password } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    try {
      await (this.creating() ? this.auth.register(email, password) : this.auth.login(email, password));
      await this.router.navigateByUrl(this.route.snapshot.queryParamMap.get('retorno') ?? '/');
    } catch (error) {
      this.error.set(errorMessage(error, this.creating()));
    } finally {
      this.busy.set(false);
    }
  }
}
