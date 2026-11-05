import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import {
  Evidence,
  InvestigationDetail,
  InvestigationOutcome,
  InvestigationStatus,
  UserRef,
} from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { Fact, toFacts } from '../../shared/facts';
import { HumanizePipe } from '../../shared/humanize.pipe';

export const MIN_SUMMARY_LENGTH = 20;

@Component({
  selector: 'app-investigation-detail',
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatProgressBarModule, MatSnackBarModule, EmptyStateComponent, HumanizePipe],
  templateUrl: './investigation-detail.component.html',
  styleUrl: './investigation-detail.component.scss',
})
export class InvestigationDetailComponent {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  /** Route parameter (component input binding). */
  readonly id = input.required<string>();

  readonly detail = signal<InvestigationDetail | null>(null);
  readonly error = signal<string | null>(null);
  readonly assignees = signal<UserRef[]>([]);
  readonly busy = signal(false);
  readonly actionError = signal<string | null>(null);

  // Form state
  nextStatus: InvestigationStatus | '' = '';
  reason = '';
  outcome: InvestigationOutcome | '' = '';
  outcomeSummary = '';
  assigneeId: number | null = null;
  note = '';

  readonly minSummary = MIN_SUMMARY_LENGTH;
  readonly resolved = computed(() => this.detail()?.investigation.status === 'RESOLVED');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    this.api.assignees().subscribe({ next: (u) => this.assignees.set(u), error: () => this.assignees.set([]) });
  }

  load(raw: string = this.id()): void {
    this.error.set(null);
    const id = Number(raw);
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Invalid investigation reference.');
      return;
    }
    this.api.investigation(id).subscribe({
      next: (d) => this.applyDetail(d),
      error: (err) => this.error.set(errorMessage(err, 'The investigation could not be loaded.')),
    });
  }

  private applyDetail(d: InvestigationDetail): void {
    this.detail.set(d);
    this.assigneeId = d.investigation.assignedTo?.id ?? null;
    this.nextStatus = '';
    this.reason = '';
    this.outcome = '';
    this.outcomeSummary = '';
  }

  needsReason(): boolean {
    return this.nextStatus === 'ESCALATED' || (this.resolved() && this.nextStatus !== '');
  }

  canSubmitStatus(): boolean {
    if (!this.nextStatus || this.busy()) {
      return false;
    }
    if (this.needsReason() && !this.reason.trim()) {
      return false;
    }
    if (this.nextStatus === 'RESOLVED') {
      return !!this.outcome && this.outcomeSummary.trim().length >= MIN_SUMMARY_LENGTH
        && !!this.detail()?.investigation.assignedTo;
    }
    return true;
  }

  submitStatus(): void {
    const d = this.detail();
    if (!d || !this.nextStatus || !this.canSubmitStatus()) {
      return;
    }
    const resolving = this.nextStatus === 'RESOLVED';
    this.run(
      this.api.changeInvestigationStatus(d.investigation.id, {
        status: this.nextStatus,
        reason: this.reason.trim() || undefined,
        outcome: resolving && this.outcome ? this.outcome : undefined,
        outcomeSummary: resolving ? this.outcomeSummary.trim() : undefined,
        version: d.version,
      }),
      resolving ? 'Outcome recorded' : 'Status updated',
    );
  }

  submitAssignment(): void {
    const d = this.detail();
    if (!d) {
      return;
    }
    this.run(this.api.assignInvestigation(d.investigation.id, this.assigneeId, d.version), 'Assignment updated');
  }

  assignmentChanged(): boolean {
    return (this.detail()?.investigation.assignedTo?.id ?? null) !== this.assigneeId;
  }

  submitNote(): void {
    const d = this.detail();
    const text = this.note.trim();
    if (!d || !text) {
      return;
    }
    this.run(this.api.addInvestigationNote(d.investigation.id, text), 'Note added', () => (this.note = ''));
  }

  private run(request: Observable<InvestigationDetail>, success: string, after?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: (d) => {
        this.busy.set(false);
        this.applyDetail(d);
        after?.();
        this.snack.open(success, 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.busy.set(false);
        this.actionError.set(errorMessage(err, 'The change could not be saved.'));
        if (err?.status === 409) {
          this.load();
        }
      },
    });
  }

  evidenceFacts(e: Evidence): Fact[] {
    const content = e.content ?? {};
    const nested = content['details'];
    const base = toFacts(content, ['details', 'reason']);
    return nested && typeof nested === 'object'
      ? [...base, ...toFacts(nested as Record<string, unknown>, ['thresholds', 'relatedTransactionIds'])]
      : base;
  }

  evidenceLabel(e: Evidence): { css: string; text: string } {
    if (e.aiGenerated) {
      return { css: 'analytical', text: 'AI-generated · requires review' };
    }
    return e.evidenceType === 'TRANSACTION'
      ? { css: 'verified', text: 'Verified data snapshot' }
      : { css: 'analytical', text: 'Analytical finding' };
  }

  activityText(details: Record<string, unknown>): string {
    const from = details['from'];
    const to = details['to'];
    if (from && to) {
      return `${String(from).replace(/_/g, ' ').toLowerCase()} → ${String(to).replace(/_/g, ' ').toLowerCase()}`;
    }
    if ('toUsername' in details) {
      return details['toUsername'] ? `to ${details['toUsername']}` : 'unassigned';
    }
    return '';
  }
}
