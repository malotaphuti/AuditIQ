import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { InvestigationQuery, InvestigationRow, InvestigationStatus, InvestigationSummary } from '../../core/models';
import { BarItem, BarListComponent } from '../../shared/charts/bar-list.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

export const INVESTIGATION_STATUSES: InvestigationStatus[] = [
  'NEW', 'UNDER_REVIEW', 'AWAITING_EVIDENCE', 'ESCALATED', 'RESOLVED',
];

interface Filters {
  q: string;
  status: string;
  priority: string;
  assignee: string;
}

const DEFAULT_FILTERS: Filters = { q: '', status: 'OPEN', priority: '', assignee: '' };

@Component({
  selector: 'app-investigations',
  imports: [
    CurrencyPipe, DatePipe, DecimalPipe, FormsModule, RouterLink, MatTableModule, MatSortModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatProgressBarModule, EmptyStateComponent, HumanizePipe, BarListComponent,
  ],
  templateUrl: './investigations.component.html',
  styleUrl: './investigations.component.scss',
})
export class InvestigationsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly statuses = INVESTIGATION_STATUSES;
  readonly priorities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
  readonly columns = ['investigationRef', 'title', 'status', 'priority', 'assignedTo', 'exceptionRef', 'amount',
    'updatedAt'];

  filters: Filters = { ...DEFAULT_FILTERS };
  sort: Sort = { active: 'updatedAt', direction: 'desc' };
  page = 0;
  size = 25;

  readonly rows = signal<InvestigationRow[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly summary = signal<InvestigationSummary | null>(null);

  readonly byStatus = computed<BarItem[]>(() => {
    const s = this.summary();
    return s ? INVESTIGATION_STATUSES.map((key) => ({ key, count: s.byStatus[key] ?? 0, value: 0 })) : [];
  });
  readonly totalInvestigations = computed(() =>
    Object.values(this.summary()?.byStatus ?? {}).reduce((a, b) => a + b, 0),
  );

  ngOnInit(): void {
    this.api.investigationSummary().subscribe({
      next: (s) => this.summary.set(s),
      error: () => this.summary.set(null),
    });
    this.search();
  }

  query(): InvestigationQuery {
    const f = this.filters;
    return {
      q: f.q.trim() || undefined,
      status: f.status || undefined,
      priority: f.priority || undefined,
      assignee: f.assignee || undefined,
      sort: this.sort.active,
      direction: this.sort.direction === 'asc' ? 'asc' : 'desc',
      page: this.page,
      size: this.size,
    };
  }

  search(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.investigations(this.query()).subscribe({
      next: (res) => {
        this.rows.set(res.content);
        this.total.set(res.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Could not load investigations.'));
        this.loading.set(false);
      },
    });
  }

  apply(): void {
    this.page = 0;
    this.search();
  }

  reset(): void {
    this.filters = { ...DEFAULT_FILTERS };
    this.apply();
  }

  isDefault(): boolean {
    return JSON.stringify(this.filters) === JSON.stringify(DEFAULT_FILTERS);
  }

  onSort(sort: Sort): void {
    this.sort = sort.direction ? sort : { active: 'updatedAt', direction: 'desc' };
    this.apply();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.search();
  }

  open(row: InvestigationRow): void {
    this.router.navigate(['/investigations', row.id]);
  }

  formatHours(hours: number | null): string {
    if (hours === null) {
      return '—';
    }
    if (hours < 0.1) {
      return 'under 6 min';
    }
    if (hours < 1) {
      return `${Math.round(hours * 60)} min`;
    }
    return hours >= 48 ? `${(hours / 24).toFixed(1)} days` : `${hours.toFixed(1)} h`;
  }
}
