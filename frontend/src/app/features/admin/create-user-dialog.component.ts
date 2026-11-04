import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { RoleName, UserAccount } from '../../core/models';

/** Mirrors the server-side password policy so users get immediate feedback. */
export const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$/;

@Component({
  selector: 'app-create-user-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title>New user</h2>
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <mat-dialog-content class="form-grid">
        <mat-form-field>
          <mat-label>Full name</mat-label>
          <input matInput formControlName="fullName" />
          <mat-error>Full name is required</mat-error>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Username</mat-label>
          <input matInput formControlName="username" autocomplete="off" />
          <mat-error>3–50 letters, digits, dot, underscore or hyphen</mat-error>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Email</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="off" />
          <mat-error>A valid email is required</mat-error>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Temporary password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="new-password" />
          <mat-hint>12+ characters with upper, lower, digit and symbol</mat-hint>
          <mat-error>12+ characters with upper, lower, digit and symbol</mat-error>
        </mat-form-field>
        <fieldset class="roles">
          <legend>Roles</legend>
          @for (r of roleOptions; track r.value) {
            <mat-checkbox [checked]="roles().includes(r.value)" (change)="toggleRole(r.value, $event.checked)">
              {{ r.label }}
            </mat-checkbox>
          }
          @if (roles().length === 0) {
            <div class="field-error">Select at least one role</div>
          }
        </fieldset>
        @if (error()) {
          <div class="field-error" role="alert">{{ error() }}</div>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Create user</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .roles {
      border: 1px solid var(--aiq-border);
      border-radius: 8px;
      padding: 8px 12px;
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
    }
    .field-error {
      color: var(--aiq-red);
      font-size: 0.85rem;
      width: 100%;
    }
  `,
})
export class CreateUserDialogComponent {
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<CreateUserDialogComponent, UserAccount>);

  readonly roleOptions: { value: RoleName; label: string }[] = [
    { value: 'AUDITOR', label: 'Auditor' },
    { value: 'AUDIT_MANAGER', label: 'Audit Manager' },
    { value: 'ADMIN', label: 'Administrator' },
  ];

  readonly form = inject(NonNullableFormBuilder).group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    username: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9._-]{3,50}$/)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', [Validators.required, Validators.pattern(PASSWORD_PATTERN)]],
  });
  readonly roles = signal<RoleName[]>(['AUDITOR']);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  toggleRole(role: RoleName, checked: boolean): void {
    this.roles.update((rs) => (checked ? [...new Set([...rs, role])] : rs.filter((r) => r !== role)));
  }

  submit(): void {
    if (this.form.invalid || this.roles().length === 0) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.createUser({ ...this.form.getRawValue(), roles: this.roles() }).subscribe({
      next: (user) => this.ref.close(user),
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'Could not create user.'));
      },
    });
  }
}
