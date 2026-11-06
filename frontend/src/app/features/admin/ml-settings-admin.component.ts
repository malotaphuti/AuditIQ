import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { MlSettings } from '../../core/models';

type Draft = Omit<MlSettings, 'updatedBy' | 'updatedAt'>;

/** Administrator view: anomaly-score thresholds and the minimum priority that raises a potential exception. */
@Component({
  selector: 'app-ml-settings-admin',
  imports: [DatePipe, FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatSnackBarModule],
  template: `
    <p class="muted small intro">
      Isolation Forest scores lie between 0 and 1; about 0.5 or lower is ordinary. Scores measure how unusual a
      transaction is – they are not fraud probabilities. Changes apply on the next detection run and are audited.
    </p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (draft; as d) {
      <form (ngSubmit)="save()" class="form" aria-label="Anomaly model thresholds">
        <mat-form-field subscriptSizing="dynamic">
          <mat-label>Flag threshold (low priority)</mat-label>
          <input matInput type="number" name="anomaly" [(ngModel)]="d.anomalyThreshold" min="0.01" max="0.99" step="0.01" required />
        </mat-form-field>
        <mat-form-field subscriptSizing="dynamic">
          <mat-label>Medium priority from</mat-label>
          <input matInput type="number" name="medium" [(ngModel)]="d.mediumThreshold" min="0.01" max="0.99" step="0.01" required />
        </mat-form-field>
        <mat-form-field subscriptSizing="dynamic">
          <mat-label>High priority from</mat-label>
          <input matInput type="number" name="high" [(ngModel)]="d.highThreshold" min="0.01" max="0.99" step="0.01" required />
        </mat-form-field>
        <mat-form-field subscriptSizing="dynamic">
          <mat-label>Raise exceptions from</mat-label>
          <mat-select name="minPriority" [(ngModel)]="d.exceptionMinPriority">
            <mat-option value="LOW">Low priority</mat-option>
            <mat-option value="MEDIUM">Medium priority</mat-option>
            <mat-option value="HIGH">High priority</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="actions">
          <button mat-flat-button type="submit" [disabled]="saving() || !ordered()">Save thresholds</button>
          @if (!ordered()) { <span class="error small">Thresholds must increase: flag ≤ medium ≤ high.</span> }
        </div>
      </form>
      @if (current()?.updatedBy; as by) {
        <p class="muted small">Last changed by {{ by }} {{ current()?.updatedAt | date: 'medium' }}.</p>
      }
    }
  `,
  styles: `
    :host { display: block; padding: 16px; }
    .intro { margin: 0 0 16px; max-width: 760px; }
    .form { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; align-items: center; }
    .actions { display: flex; gap: 12px; align-items: center; grid-column: 1 / -1; }
    .error { color: var(--aiq-red); }
  `,
})
export class MlSettingsAdminComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly current = signal<MlSettings | null>(null);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  draft: Draft | null = null;

  ngOnInit(): void {
    this.api.mlSettings().subscribe({
      next: (s) => this.adopt(s),
      error: (err) => this.error.set(errorMessage(err, 'Model settings could not be loaded.')),
    });
  }

  ordered(): boolean {
    const d = this.draft;
    return !!d && d.anomalyThreshold > 0 && d.anomalyThreshold <= d.mediumThreshold
      && d.mediumThreshold <= d.highThreshold && d.highThreshold < 1;
  }

  save(): void {
    if (!this.draft || !this.ordered()) {
      return;
    }
    this.saving.set(true);
    this.api.updateMlSettings(this.draft).subscribe({
      next: (s) => {
        this.saving.set(false);
        this.adopt(s);
        this.snack.open('Anomaly thresholds saved; they apply on the next detection run', 'OK', { duration: 4000 });
      },
      error: (err) => {
        this.saving.set(false);
        this.snack.open(errorMessage(err, 'The thresholds could not be saved.'), 'Dismiss', { duration: 6000 });
      },
    });
  }

  private adopt(s: MlSettings): void {
    this.current.set(s);
    this.draft = {
      anomalyThreshold: Number(s.anomalyThreshold),
      mediumThreshold: Number(s.mediumThreshold),
      highThreshold: Number(s.highThreshold),
      exceptionMinPriority: s.exceptionMinPriority,
    };
  }
}
