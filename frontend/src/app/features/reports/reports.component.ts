import { DatePipe, DecimalPipe, LowerCasePipe, PercentPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import { ModelPerformance, ModelVersion } from '../../core/models';
import { BarItem, BarListComponent } from '../../shared/charts/bar-list.component';
import { HistogramBin, HistogramComponent, ThresholdMarker } from '../../shared/charts/histogram.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

@Component({
  selector: 'app-reports',
  imports: [DatePipe, DecimalPipe, LowerCasePipe, PercentPipe, RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule,
    MatProgressSpinnerModule, MatSnackBarModule, EmptyStateComponent, HumanizePipe, BarListComponent,
    HistogramComponent],
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.scss',
})
export class ReportsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly canRun = inject(AuthService).hasAnyRole(['ADMIN', 'AUDITOR']);
  readonly perf = signal<ModelPerformance | null>(null);
  readonly versions = signal<ModelVersion[]>([]);
  readonly loading = signal(true);
  readonly noModel = signal(false);
  readonly error = signal<string | null>(null);
  readonly busy = signal<'train' | 'detect' | null>(null);
  readonly selectedVersion = signal<number | null>(null);

  readonly markers = computed<ThresholdMarker[]>(() => {
    const t = this.perf()?.thresholds;
    return t ? [{ value: t.anomaly, label: 'flag' }, { value: t.medium, label: 'med' }, { value: t.high, label: 'high' }]
      : [];
  });
  readonly labelledBins = computed<HistogramBin[]>(() =>
    (this.perf()?.scoreDistribution ?? []).map((b) => ({ from: b.from, to: b.to, count: b.labelled })));
  readonly otherBins = computed<HistogramBin[]>(() =>
    (this.perf()?.scoreDistribution ?? []).map((b) => ({ from: b.from, to: b.to, count: b.unlabelled })));
  readonly overlap = computed<BarItem[]>(() => {
    const o = this.perf()?.comparison?.overlap;
    return o ? [
      { key: 'Detected by both', count: o.bothDetected, value: 0 },
      { key: 'Rules only', count: o.rulesOnly, value: 0 },
      { key: 'Model only', count: o.modelOnly, value: 0 },
      { key: 'Missed by both', count: o.neither, value: 0 },
    ] : [];
  });
  readonly fpByType = computed<BarItem[]>(() => this.toBars(this.perf()?.falsePositives?.byTransactionType));
  readonly fpByChannel = computed<BarItem[]>(() => this.toBars(this.perf()?.falsePositives?.byChannel));
  readonly fpByFactor = computed<BarItem[]>(() => this.toBars(this.perf()?.falsePositives?.byTopFactor));

  ngOnInit(): void {
    this.load();
  }

  load(versionId?: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.modelVersions().subscribe({ next: (v) => this.versions.set(v), error: () => this.versions.set([]) });
    this.api.modelPerformance(versionId).subscribe({
      next: (p) => {
        this.perf.set(p);
        this.selectedVersion.set(p.modelVersionId);
        this.noModel.set(false);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.perf.set(null);
        if (err?.status === 409) {
          this.noModel.set(true);
        } else {
          this.error.set(errorMessage(err, 'Model performance could not be loaded.'));
        }
      },
    });
  }

  train(): void {
    this.busy.set('train');
    this.api.trainModel().subscribe({
      next: (r) => {
        this.busy.set(null);
        this.snack.open(`Model ${r.version} trained on ${r.trainingRows.toLocaleString()} transactions`, 'OK',
          { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.busy.set(null);
        this.snack.open(errorMessage(err, 'Training failed.'), 'Dismiss', { duration: 6000 });
      },
    });
  }

  detect(): void {
    this.busy.set('detect');
    this.api.detectAnomalies().subscribe({
      next: (r) => {
        this.busy.set(null);
        this.snack.open(`${r.exceptionsCreated.toLocaleString()} new potential exception(s) from model ${r.version}`,
          'OK', { duration: 5000 });
        this.load(this.selectedVersion() ?? undefined);
      },
      error: (err) => {
        this.busy.set(null);
        this.snack.open(errorMessage(err, 'Detection failed.'), 'Dismiss', { duration: 6000 });
      },
    });
  }

  view(version: ModelVersion): void {
    this.load(version.id);
  }

  hyperparameters(p: ModelPerformance): string {
    return Object.entries(p.hyperparameters).map(([k, v]) => `${k.replace(/_/g, ' ')} ${v}`).join(' · ');
  }

  private toBars(items: { key: string; count: number }[] | undefined): BarItem[] {
    return (items ?? []).map((i) => ({ key: i.key, count: i.count, value: 0 }));
  }
}
