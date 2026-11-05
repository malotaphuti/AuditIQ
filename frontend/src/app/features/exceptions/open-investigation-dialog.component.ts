import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import { ExceptionRow, InvestigationDetail, Severity, UserRef } from '../../core/models';
import { HumanizePipe } from '../../shared/humanize.pipe';

/** Opens an investigation for a potential exception. Closes with the created investigation. */
@Component({
  selector: 'app-open-investigation-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    HumanizePipe],
  template: `
    <h2 mat-dialog-title>Open investigation</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content>
        <p class="muted small">
          For {{ exception.exceptionRef }} ({{ exception.ruleName ?? 'model finding' }}). The case starts as
          <strong>New</strong>; the outcome is recorded later by a human reviewer.
        </p>
        <mat-form-field class="full">
          <mat-label>Title</mat-label>
          <input matInput formControlName="title" maxlength="200" [placeholder]="defaultTitle" />
          <mat-hint>Leave blank to use “{{ defaultTitle }}”</mat-hint>
        </mat-form-field>
        <div class="row">
          <mat-form-field>
            <mat-label>Priority</mat-label>
            <mat-select formControlName="priority">
              @for (p of priorities; track p) { <mat-option [value]="p">{{ p | humanize }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Assign to</mat-label>
            <mat-select formControlName="assigneeId">
              <mat-option [value]="null">Unassigned</mat-option>
              @for (u of assignees(); track u.id) { <mat-option [value]="u.id">{{ u.fullName }}</mat-option> }
            </mat-select>
          </mat-form-field>
        </div>
        <mat-form-field class="full">
          <mat-label>Initial note (optional)</mat-label>
          <textarea matInput formControlName="note" rows="3" maxlength="4000"></textarea>
        </mat-form-field>
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Open investigation</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .full { width: 100%; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .error { color: var(--aiq-red); }
    @media (max-width: 599px) { .row { grid-template-columns: 1fr; } }
  `,
})
export class OpenInvestigationDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<OpenInvestigationDialogComponent, InvestigationDetail>);
  private readonly me = inject(AuthService).user();
  readonly exception = inject<ExceptionRow>(MAT_DIALOG_DATA);

  readonly priorities: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
  readonly defaultTitle = `${this.exception.ruleName ?? 'Model finding'} – ${this.exception.transactionId}`;
  readonly assignees = signal<UserRef[]>([]);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  readonly form = inject(FormBuilder).group({
    title: [''],
    priority: [this.exception.severity as Severity, Validators.required],
    assigneeId: [null as number | null],
    note: [''],
  });

  ngOnInit(): void {
    this.api.assignees().subscribe({
      next: (users) => {
        this.assignees.set(users);
        if (this.me && users.some((u) => u.id === this.me!.id)) {
          this.form.controls.assigneeId.setValue(this.me.id);
        }
      },
      error: () => this.assignees.set([]),
    });
  }

  submit(): void {
    if (this.form.invalid || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    this.api.createInvestigation({
      exceptionId: this.exception.id,
      title: v.title?.trim() || undefined,
      priority: v.priority ?? undefined,
      assigneeId: v.assigneeId ?? undefined,
      note: v.note?.trim() || undefined,
    }).subscribe({
      next: (created) => this.ref.close(created),
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'The investigation could not be opened.'));
      },
    });
  }
}
