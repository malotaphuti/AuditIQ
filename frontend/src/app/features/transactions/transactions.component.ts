import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { TransactionAnalytics, TransactionQuery, TransactionRow } from '../../core/models';
import { BarListComponent } from '../../shared/charts/bar-list.component';
import { SeriesPoint, TimeSeriesChartComponent } from '../../shared/charts/time-series-chart.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';
import { TransactionDetailDialogComponent } from './transaction-detail-dialog.component';

export const TRANSACTION_TYPES = ['PURCHASE', 'TRANSFER', 'WITHDRAWAL', 'DEPOSIT', 'VENDOR_PAYMENT', 'DEBIT_ORDER'];
export const CHANNELS = ['POS', 'ONLINE', 'MOBILE', 'BRANCH', 'ATM', 'EFT'];
export const STATUSES = ['COMPLETED', 'PENDING', 'FAILED', 'REVERSED'];
export const CATEGORIES = [
  'GROCERIES', 'FUEL', 'RESTAURANTS', 'RETAIL', 'ELECTRONICS', 'TRAVEL', 'UTILITIES', 'TELECOMS', 'HEALTHCARE',
  'ENTERTAINMENT', 'PROFESSIONAL_SERVICES', 'IT_SERVICES', 'OFFICE_SUPPLIES', 'CONSTRUCTION', 'LOGISTICS',
];

interface Filters {
  q: string;
  accountId: string;
  type: string;
  channel: string;
  category: string;
  status: string;
  from: string;
  to: string;
  minAmount: number | null;
  maxAmount: number | null;
}

const EMPTY_FILTERS: Filters = {
  q: '', accountId: '', type: '', channel: '', category: '', status: '', from: '', to: '',
  minAmount: null, maxAmount: null,
};

@Component({
  selector: 'app-transactions',
  imports: [
    CurrencyPipe, DatePipe, DecimalPipe, FormsModule, MatTableModule, MatSortModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatButtonToggleModule, MatIconModule,
    MatProgressBarModule, MatDialogModule, EmptyStateComponent, HumanizePipe, BarListComponent,
    TimeSeriesChartComponent,
  ],
  templateUrl: './transactions.component.html',
  styleUrl: './transactions.component.scss',
})
export class TransactionsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);

  readonly types = TRANSACTION_TYPES;
  readonly channels = CHANNELS;
  readonly statuses = STATUSES;
  readonly categories = CATEGORIES;
  readonly columns = ['transactionDate', 'transactionId', 'accountId', 'type', 'merchant', 'amount', 'status'];

  filters: Filters = { ...EMPTY_FILTERS };
  sort: Sort = { active: 'transactionDate', direction: 'desc' };
  page = 0;
  size = 25;

  readonly rows = signal<TransactionRow[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly analytics = signal<TransactionAnalytics | null>(null);
  readonly analyticsError = signal<string | null>(null);
  categoryMeasure: 'count' | 'value' = 'value';

  readonly monthly = computed<SeriesPoint[]>(() =>
    (this.analytics()?.monthly ?? []).map((b) => ({ key: b.key, value: b.count, secondary: b.value })),
  );

  ngOnInit(): void {
    this.loadAnalytics();
    this.search();
  }

  loadAnalytics(): void {
    this.api.transactionAnalytics().subscribe({
      next: (a) => this.analytics.set(a),
      error: (err) => this.analyticsError.set(errorMessage(err, 'Analytics unavailable.')),
    });
  }

  query(): TransactionQuery {
    const f = this.filters;
    const sortKey = this.sort.active === 'amount' || this.sort.active === 'accountId' ? this.sort.active
      : 'transactionDate';
    return {
      q: f.q.trim() || undefined,
      accountId: f.accountId.trim() || undefined,
      type: f.type || undefined,
      channel: f.channel || undefined,
      category: f.category || undefined,
      status: f.status || undefined,
      from: f.from || undefined,
      to: f.to || undefined,
      minAmount: f.minAmount ?? undefined,
      maxAmount: f.maxAmount ?? undefined,
      sort: sortKey,
      direction: this.sort.direction === 'asc' ? 'asc' : 'desc',
      page: this.page,
      size: this.size,
    };
  }

  search(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.transactions(this.query()).subscribe({
      next: (res) => {
        this.rows.set(res.content);
        this.total.set(res.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load transactions.'));
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
    this.sort = sort.direction ? sort : { active: 'transactionDate', direction: 'desc' };
    this.apply();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.search();
  }

  open(row: TransactionRow): void {
    this.dialog.open(TransactionDetailDialogComponent, {
      data: row.transactionId,
      width: '760px',
      maxWidth: '95vw',
      autoFocus: 'dialog',
    });
  }
}
