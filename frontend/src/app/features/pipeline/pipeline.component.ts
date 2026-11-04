import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { Subscription, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import { PipelineRunDetail, PipelineRunSummary, RunPipelineRequest } from '../../core/models';
import { BarItem, BarListComponent } from '../../shared/charts/bar-list.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { DurationPipe } from '../../shared/duration.pipe';
import { HumanizePipe } from '../../shared/humanize.pipe';

const POLL_MS = 2000;

@Component({
  selector: 'app-pipeline',
  imports: [
    DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatIconModule, MatTableModule, MatPaginatorModule, MatProgressBarModule,
    MatProgressSpinnerModule, MatSnackBarModule, EmptyStateComponent, HumanizePipe, DurationPipe, BarListComponent,
  ],
  templateUrl: './pipeline.component.html',
  styleUrl: './pipeline.component.scss',
})
export class PipelineComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly canRun = inject(AuthService).hasAnyRole(['ADMIN', 'AUDITOR']);
  readonly columns = ['runRef', 'status', 'trigger', 'startedAt', 'duration', 'records'];

  // Run form
  source: 'GENERATED' | 'CSV' = 'GENERATED';
  transactionCount = 110000;
  seed: number | null = null;
  folder: string | null = null;
  readonly folders = signal<string[]>([]);
  readonly submitting = signal(false);

  // History & detail
  readonly runs = signal<PipelineRunSummary[]>([]);
  readonly total = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly selected = signal<PipelineRunDetail | null>(null);
  page = 0;
  size = 10;

  private poll?: Subscription;

  readonly anyActive = computed(() =>
    this.runs().some((r) => r.status === 'RUNNING' || r.status === 'PENDING') ||
    this.selected()?.run.status === 'RUNNING',
  );

  readonly reasons = computed<BarItem[]>(() => this.toBars(this.selected()?.qualityReport?.rejectionReasons));
  readonly missing = computed<BarItem[]>(() => this.toBars(this.selected()?.qualityReport?.missingValues));
  readonly standardisations = computed<BarItem[]>(() => {
    const step = this.selected()?.steps.find((s) => s.name === 'validate_and_standardise');
    return this.toBars((step?.details?.['standardisations'] as Record<string, number> | undefined) ?? undefined);
  });

  ngOnInit(): void {
    this.loadRuns(true);
    if (this.canRun) {
      this.api.pipelineSources().subscribe({
        next: (s) => this.folders.set(s.folders),
        error: () => this.folders.set([]),
      });
    }
  }

  loadRuns(selectLatest = false): void {
    this.api.pipelineRuns(this.page, this.size).subscribe({
      next: (res) => {
        this.runs.set(res.content);
        this.total.set(res.totalElements);
        this.loading.set(false);
        this.error.set(null);
        const current = this.selected()?.run.id;
        if (selectLatest && res.content.length > 0 && current === undefined) {
          this.select(res.content[0]);
        } else if (current !== undefined) {
          this.refreshSelected(current);
        }
        this.ensurePolling();
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load pipeline runs.'));
      },
    });
  }

  select(run: PipelineRunSummary): void {
    this.refreshSelected(run.id);
  }

  private refreshSelected(id: number): void {
    this.api.pipelineRun(id).subscribe({
      next: (detail) => this.selected.set(detail),
      error: (err) => this.snack.open(errorMessage(err), 'Dismiss', { duration: 5000 }),
    });
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.loadRuns();
  }

  run(): void {
    const request: RunPipelineRequest =
      this.source === 'GENERATED'
        ? { source: 'GENERATED', transactionCount: this.transactionCount, seed: this.seed ?? undefined }
        : { source: 'CSV', folder: this.folder };
    this.submitting.set(true);
    this.api.runPipeline(request).subscribe({
      next: (started) => {
        this.submitting.set(false);
        this.snack.open(`Pipeline run ${started.runRef} started`, 'OK', { duration: 3000 });
        this.page = 0;
        this.refreshSelected(started.runId);
        this.loadRuns();
      },
      error: (err) => {
        this.submitting.set(false);
        this.snack.open(errorMessage(err, 'The pipeline could not be started.'), 'Dismiss', { duration: 6000 });
      },
    });
  }

  /** Polls while a run is active and stops automatically once every run has finished. */
  private ensurePolling(): void {
    if (this.anyActive() && !this.poll) {
      this.poll = timer(POLL_MS, POLL_MS)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.loadRuns());
    } else if (!this.anyActive() && this.poll) {
      this.poll.unsubscribe();
      this.poll = undefined;
    }
  }

  private toBars(record: Record<string, number> | undefined): BarItem[] {
    return Object.entries(record ?? {}).map(([key, count]) => ({ key, count, value: count }));
  }
}
