import { CurrencyPipe, DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { ExceptionDetail, InvestigationDetail } from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { Fact, toFacts } from '../../shared/facts';
import { HumanizePipe } from '../../shared/humanize.pipe';
import { OpenInvestigationDialogComponent } from './open-investigation-dialog.component';

const HIDDEN_DETAIL_KEYS = ['thresholds', 'relatedTransactionIds', 'ruleVersion'];

@Component({
  selector: 'app-exception-detail',
  imports: [CurrencyPipe, DatePipe, NgTemplateOutlet, RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule,
    MatDialogModule, EmptyStateComponent, HumanizePipe],
  templateUrl: './exception-detail.component.html',
  styleUrl: './exception-detail.component.scss',
})
export class ExceptionDetailComponent {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  /** Route parameter (component input binding). */
  readonly id = input.required<string>();

  readonly detail = signal<ExceptionDetail | null>(null);
  readonly error = signal<string | null>(null);

  readonly facts = computed<Fact[]>(() => toFacts(this.detail()?.details, HIDDEN_DETAIL_KEYS));
  readonly thresholds = computed<Fact[]>(() =>
    toFacts(this.detail()?.details?.['thresholds'] as Record<string, unknown> | undefined),
  );
  readonly ruleVersion = computed(() => this.detail()?.details?.['ruleVersion'] as number | undefined);

  constructor() {
    // Reload whenever the route parameter changes (e.g. following a link to another finding).
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  load(raw: string = this.id()): void {
    this.error.set(null);
    this.detail.set(null);
    const id = Number(raw);
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set('Invalid exception reference.');
      return;
    }
    this.api.exception(id).subscribe({
      next: (d) => this.detail.set(d),
      error: (err) => this.error.set(errorMessage(err, 'The exception could not be loaded.')),
    });
  }

  openInvestigation(): void {
    const d = this.detail();
    if (!d) {
      return;
    }
    this.dialog
      .open<OpenInvestigationDialogComponent, unknown, InvestigationDetail>(OpenInvestigationDialogComponent, {
        data: d.exception,
        width: '560px',
        maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          this.router.navigate(['/investigations', created.investigation.id]);
        }
      });
  }
}
