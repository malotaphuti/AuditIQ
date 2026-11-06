import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import { DashBucket, RiskDashboard } from '../../core/models';
import { BarItem, BarListComponent } from '../../shared/charts/bar-list.component';
import { SeriesPoint, TimeSeriesChartComponent } from '../../shared/charts/time-series-chart.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const INVESTIGATION_STATUSES = ['NEW', 'UNDER_REVIEW', 'AWAITING_EVIDENCE', 'ESCALATED', 'RESOLVED'];

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** In-app equivalent of the Power BI report: the same four pages, computed from the same analytics views. */
@Component({
  selector: 'app-risk-analytics',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, FormsModule, RouterLink, MatTabsModule, MatButtonModule,
    MatButtonToggleModule, MatFormFieldModule, MatInputModule, MatIconModule, MatProgressBarModule,
    MatSnackBarModule, EmptyStateComponent, HumanizePipe, BarListComponent, TimeSeriesChartComponent],
  templateUrl: './risk-analytics.component.html',
  styleUrl: './risk-analytics.component.scss',
})
export class RiskAnalyticsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly canExport = inject(AuthService).hasAnyRole(['AUDITOR', 'AUDIT_MANAGER']);
  readonly data = signal<RiskDashboard | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly datasets = signal<string[]>([]);
  readonly downloading = signal<string | null>(null);

  from = '';
  to = '';
  categoryMeasure: 'count' | 'value' = 'value';

  readonly daily = computed<SeriesPoint[]>(() =>
    (this.data()?.transactions.daily ?? []).map((p) => ({ key: p.date, value: p.count, secondary: p.value })));
  readonly monthly = computed<SeriesPoint[]>(() =>
    (this.data()?.transactions.monthly ?? []).map((b) => ({ key: b.key, value: b.count, secondary: b.value ?? 0 })));
  readonly flaggedRate = computed<SeriesPoint[]>(() =>
    (this.data()?.risk.monthlyTrend ?? []).map((m) => ({
      key: m.month, value: m.flaggedRatePercent, secondary: m.flaggedTransactions })));
  readonly ruleTrend = computed<SeriesPoint[]>(() =>
    (this.data()?.risk.monthlyTrend ?? []).map((m) => ({ key: m.month, value: m.ruleFindings })));
  readonly modelTrend = computed<SeriesPoint[]>(() =>
    (this.data()?.risk.monthlyTrend ?? []).map((m) => ({ key: m.month, value: m.modelFindings })));

  readonly bySeverity = computed<BarItem[]>(() => {
    const s = this.data()?.risk.bySeverity ?? {};
    return SEVERITIES.map((key) => ({ key, count: s[key] ?? 0, value: 0 }));
  });
  readonly byDetector = computed<BarItem[]>(() =>
    (this.data()?.risk.byDetector ?? []).map((d) => ({
      key: d.key === 'ML' ? 'Anomaly model' : `${d.key} ${d.name}`, count: d.exceptions, value: 0 })));
  readonly overlap = computed<BarItem[]>(() => {
    const o = this.data()?.risk.ruleVsModel;
    return o ? [
      { key: 'Audit rules only', count: o.rulesOnly, value: 0 },
      { key: 'Anomaly model only', count: o.modelOnly, value: 0 },
      { key: 'Both rules and model', count: o.both, value: 0 },
    ] : [];
  });
  readonly investigationStatus = computed<BarItem[]>(() => {
    const s = this.data()?.investigations.byStatus ?? {};
    return INVESTIGATION_STATUSES.map((key) => ({ key, count: s[key] ?? 0, value: 0 }));
  });

  ngOnInit(): void {
    this.load();
    if (this.canExport) {
      this.api.analyticsDatasets().subscribe({ next: (d) => this.datasets.set(d), error: () => this.datasets.set([]) });
    }
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.riskDashboard(this.from || undefined, this.to || undefined).subscribe({
      next: (d) => {
        this.data.set(d);
        this.from = d.from;
        this.to = d.to;
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'The dashboard could not be loaded.'));
        this.loading.set(false);
      },
    });
  }

  preset(days: number | 'all'): void {
    const d = this.data();
    if (!d?.dataTo || !d.dataFrom) {
      return;
    }
    this.to = d.dataTo;
    this.from = days === 'all' ? d.dataFrom : addDays(d.dataTo, -(days - 1));
    this.load();
  }

  bars(items: DashBucket[] | undefined): BarItem[] {
    return (items ?? []).map((b) => ({ key: b.key, count: b.count, value: b.value ?? 0 }));
  }

  hours(value: number | null): string {
    if (value == null) {
      return '—';
    }
    if (value < 1) {
      return `${Math.max(1, Math.round(value * 60))} min`;
    }
    return value >= 48 ? `${(value / 24).toFixed(1)} days` : `${value.toFixed(1)} h`;
  }

  download(dataset: string): void {
    this.downloading.set(dataset);
    this.api.exportDataset(dataset).subscribe({
      next: (blob) => {
        this.downloading.set(null);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `auditiq_${dataset}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.downloading.set(null);
        this.snack.open(errorMessage(err, 'The export failed.'), 'Dismiss', { duration: 5000 });
      },
    });
  }
}
