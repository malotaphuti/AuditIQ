import { Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';

@Component({
  selector: 'app-login',
  imports: [
    ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Bound from query parameters. */
  readonly returnUrl = input<string>();
  readonly expired = input<string>();

  readonly form = inject(NonNullableFormBuilder).group({
    username: ['', [Validators.required, Validators.maxLength(50)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });

  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);
  readonly hidePassword = signal(true);

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const { username, password } = this.form.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => {
        const target = this.returnUrl();
        // Only follow same-app relative URLs to avoid open redirects.
        const safe = target && target.startsWith('/') && !target.startsWith('//') ? target : '/overview';
        this.router.navigateByUrl(safe);
      },
      error: (err) => {
        this.submitting.set(false);
        this.error.set(errorMessage(err, 'Sign-in failed.'));
      },
    });
  }
}
