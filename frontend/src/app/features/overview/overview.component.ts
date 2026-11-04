import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { Overview } from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';
import { SeriesPoint, TimeSeriesChartComponent } from '../../shared/charts/time-series-chart.component';

const STATUS_LABELS: Record<string, string> = {
  NEW: 'New',
  UNDER_REVIEW: 'Under review',
  AWAITING_EVIDENCE: 'Awaiting evidence',
  ESCALATED: 'Escalated',
  RESOLVED: 'Resolved',
};

@Component({
  selector: 'app-overview',
  imports: [
    DecimalPipe, CurrencyPipe, DatePipe, MatIconModule, MatButtonModule, MatProgressBarModule,
    MatTableModule, EmptyStateComponent, HumanizePipe, TimeSeriesChartComponent,
  ],
  templateUrl: './overview.component.html',
  styleUrl: './overview.component.scss',
})
export class OverviewComponent implements OnInit {
  private readonly api = inject(ApiService);

  readonly data = signal<Overview | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly exceptionColumns = ['exceptionRef', 'severity', 'riskCategory', 'accountId', 'source', 'detectedAt'];

  readonly investigationRows = computed(() => {
    const byStatus = this.data()?.investigationsByStatus ?? {};
    const total = Object.values(byStatus).reduce((a, b) => a + b, 0);
    return Object.entries(byStatus).map(([status, count]) => ({
      status,
      label: STATUS_LABELS[status] ?? status,
      count,
      percent: total === 0 ? 0 : (count / total) * 100,
    }));
  });

  readonly investigationTotal = computed(() => this.investigationRows().reduce((a, r) => a + r.count, 0));

  readonly trend = computed<SeriesPoint[]>(() =>
    (this.data()?.transactionTrend ?? []).map((p) => ({ key: p.date, value: p.count, secondary: p.value })),
  );

  readonly pipelineHealth = computed(() => {
    const p = this.data()?.pipeline;
    if (!p || p.totalRuns === 0 || !p.lastRun) {
      return { label: 'No runs yet', tone: 'neutral' as const };
    }
    const tone = p.lastRun.status === 'SUCCESS' ? 'good' : p.lastRun.status === 'FAILED' ? 'bad' : 'warn';
    return { label: p.lastRun.status, tone };
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.overview().subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load the overview.'));
        this.loading.set(false);
      },
    });
  }
}
