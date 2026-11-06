import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import {
  AuditRule,
  ExceptionQuery,
  ExceptionRow,
  ExceptionSummary,
  ExecuteRulesResponse,
  Severity,
} from '../../core/models';
import { BarItem, BarListComponent } from '../../shared/charts/bar-list.component';
import { DurationPipe } from '../../shared/duration.pipe';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

export const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
export const RISK_CATEGORIES = [
  'PAYMENT_INTEGRITY', 'APPROVAL_CONTROL', 'TRANSACTION_MONITORING', 'ACCOUNT_CONTROL', 'VENDOR_MANAGEMENT',
  'BEHAVIOURAL_ANOMALY',
];
export const EXCEPTION_STATUSES = ['OPEN', 'UNDER_INVESTIGATION', 'CLOSED'];

interface Filters {
  q: string;
  severity: string;
  riskCategory: string;
  ruleId: string;
  status: string;
  source: string;
  accountId: string;
  from: string;
  to: string;
}

const EMPTY_FILTERS: Filters = {
  q: '', severity: '', riskCategory: '', ruleId: '', status: '', source: '', accountId: '', from: '', to: '',
};

@Component({
  selector: 'app-exceptions',
  imports: [
    CurrencyPipe, DatePipe, DecimalPipe, FormsModule, RouterLink, MatTableModule, MatSortModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatProgressBarModule,
    MatProgressSpinnerModule, MatExpansionModule, MatSnackBarModule, EmptyStateComponent, HumanizePipe,
    DurationPipe, BarListComponent,
  ],
  templateUrl: './exceptions.component.html',
  styleUrl: './exceptions.component.scss',
})
export class ExceptionsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly snack = inject(MatSnackBar);

  readonly canExecute = inject(AuthService).hasAnyRole(['ADMIN', 'AUDITOR']);
  readonly severities = SEVERITIES;
  readonly categories = RISK_CATEGORIES;
  readonly statuses = EXCEPTION_STATUSES;
  readonly columns = ['exceptionRef', 'severity', 'rule', 'transactionDate', 'accountId', 'amount', 'score', 'reason',
    'status'];

  filters: Filters = { ...EMPTY_FILTERS };
  sort: Sort = { active: 'detectedAt', direction: 'desc' };
  page = 0;
  size = 25;

  readonly rows = signal<ExceptionRow[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly summary = signal<ExceptionSummary | null>(null);
  readonly rules = signal<AuditRule[]>([]);
  readonly rulesError = signal<string | null>(null);
  readonly executing = signal(false);
  readonly detecting = signal(false);
  readonly lastRun = signal<ExecuteRulesResponse | null>(null);

  readonly byRule = computed<BarItem[]>(() =>
    (this.summary()?.byRule ?? []).map((r) => ({ key: `${r.key} ${r.label}`, count: r.count, value: 0 })),
  );
  readonly bySeverity = computed<BarItem[]>(() => {
    const s = this.summary();
    return s ? SEVERITIES.map((key) => ({ key, count: s.bySeverity[key] ?? 0, value: 0 })) : [];
  });
  readonly byCategory = computed<BarItem[]>(() =>
    (this.summary()?.byRiskCategory ?? []).map((c) => ({ key: c.key, count: c.count, value: 0 })),
  );
  readonly neverRun = computed(() => this.rules().length > 0 && this.rules().every((r) => !r.lastExecution));

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    for (const key of Object.keys(EMPTY_FILTERS) as (keyof Filters)[]) {
      const value = qp.get(key);
      if (value) {
        this.filters[key] = value;
      }
    }
    this.loadOverview();
    this.search();
  }

  loadOverview(): void {
    this.api.exceptionSummary().subscribe({ next: (s) => this.summary.set(s), error: () => this.summary.set(null) });
    this.api.auditRules().subscribe({
      next: (r) => {
        this.rules.set(r);
        this.rulesError.set(null);
      },
      error: (err) => this.rulesError.set(errorMessage(err, 'Audit rules could not be loaded.')),
    });
  }

  runRules(): void {
    this.executing.set(true);
    this.api.executeAuditRules().subscribe({
      next: (result) => {
        this.executing.set(false);
        this.lastRun.set(result);
        const failed = result.results.filter((r) => r.status !== 'SUCCESS').length;
        this.snack.open(
          failed
            ? `${failed} rule(s) failed – see the execution summary`
            : `${result.exceptionsCreated} new potential exception(s) recorded`,
          'OK',
          { duration: 5000 },
        );
        this.loadOverview();
        this.apply();
      },
      error: (err) => {
        this.executing.set(false);
        this.snack.open(errorMessage(err, 'The audit rules could not be executed.'), 'Dismiss', { duration: 6000 });
      },
    });
  }

  runDetection(): void {
    this.detecting.set(true);
    this.api.detectAnomalies().subscribe({
      next: (result) => {
        this.detecting.set(false);
        this.snack.open(
          `Model ${result.version}: ${result.anomalies.toLocaleString()} unusual transactions, `
            + `${result.exceptionsCreated.toLocaleString()} new potential exception(s)`,
          'OK',
          { duration: 6000 },
        );
        this.loadOverview();
        this.apply();
      },
      error: (err) => {
        this.detecting.set(false);
        this.snack.open(errorMessage(err, 'Anomaly detection could not be run.'), 'Dismiss', { duration: 7000 });
      },
    });
  }

  query(): ExceptionQuery {
    const f = this.filters;
    return {
      q: f.q.trim() || undefined,
      severity: f.severity || undefined,
      riskCategory: f.riskCategory || undefined,
      ruleId: f.ruleId || undefined,
      status: f.status || undefined,
      source: f.source || undefined,
      accountId: f.accountId.trim() || undefined,
      from: f.from || undefined,
      to: f.to || undefined,
      sort: this.sort.active,
      direction: this.sort.direction === 'asc' ? 'asc' : 'desc',
      page: this.page,
      size: this.size,
    };
  }

  search(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.exceptions(this.query()).subscribe({
      next: (res) => {
        this.rows.set(res.content);
        this.total.set(res.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load exceptions.'));
        this.loading.set(false);
      },
    });
  }

  apply(): void {
    this.page = 0;
    this.search();
  }

  clear(): void {
    this.filters = { ...EMPTY_FILTERS };
    this.apply();
  }

  hasFilters(): boolean {
    return JSON.stringify(this.filters) !== JSON.stringify(EMPTY_FILTERS);
  }

  onSort(sort: Sort): void {
    this.sort = sort.direction ? sort : { active: 'detectedAt', direction: 'desc' };
    this.apply();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.search();
  }

  open(row: ExceptionRow): void {
    this.router.navigate(['/exceptions', row.id]);
  }

  thresholdEntries(rule: AuditRule): { label: string; value: string }[] {
    return Object.entries(rule.thresholdSchema).map(([key, spec]) => {
      const v = rule.thresholds[key];
      const value = Array.isArray(v) ? v.join(', ') : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v);
      return { label: spec.label ?? key, value };
    });
  }
}
