import { DatePipe, JsonPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { AuditLogEntry } from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

@Component({
  selector: 'app-audit-trail',
  imports: [
    DatePipe, JsonPipe, FormsModule, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, MatProgressBarModule, EmptyStateComponent, HumanizePipe,
  ],
  template: `
    <div class="toolbar-row">
      <mat-form-field subscriptSizing="dynamic">
        <mat-label>Action</mat-label>
        <input matInput [(ngModel)]="action" (keyup.enter)="apply()" placeholder="e.g. LOGIN_FAILURE" />
      </mat-form-field>
      <mat-form-field subscriptSizing="dynamic">
        <mat-label>Username</mat-label>
        <input matInput [(ngModel)]="username" (keyup.enter)="apply()" />
      </mat-form-field>
      <button mat-stroked-button (click)="apply()"><mat-icon>filter_list</mat-icon> Apply</button>
      @if (action || username) {
        <button mat-button (click)="clear()">Clear</button>
      }
    </div>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error()) {
      <app-empty-state icon="error_outline" title="Could not load the audit trail" [message]="error()!" />
    } @else if (!loading() && rows().length === 0) {
      <app-empty-state icon="history" title="No matching entries" message="Adjust the filters to see more activity." />
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="rows()" class="dense">
          <ng-container matColumnDef="occurredAt">
            <th mat-header-cell *matHeaderCellDef>When</th>
            <td mat-cell *matCellDef="let r">{{ r.occurredAt | date: 'medium' }}</td>
          </ng-container>
          <ng-container matColumnDef="username">
            <th mat-header-cell *matHeaderCellDef>User</th>
            <td mat-cell *matCellDef="let r">{{ r.username ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="action">
            <th mat-header-cell *matHeaderCellDef>Action</th>
            <td mat-cell *matCellDef="let r">{{ r.action | humanize }}</td>
          </ng-container>
          <ng-container matColumnDef="entity">
            <th mat-header-cell *matHeaderCellDef>Entity</th>
            <td mat-cell *matCellDef="let r">{{ r.entityType ?? '' }} {{ r.entityId ?? '' }}</td>
          </ng-container>
          <ng-container matColumnDef="outcome">
            <th mat-header-cell *matHeaderCellDef>Outcome</th>
            <td mat-cell *matCellDef="let r">
              <span class="chip" [class.sev-HIGH]="r.outcome === 'FAILURE'">{{ r.outcome | humanize }}</span>
            </td>
          </ng-container>
          <ng-container matColumnDef="details">
            <th mat-header-cell *matHeaderCellDef>Details</th>
            <td mat-cell *matCellDef="let r"><code class="details">{{ r.details | json }}</code></td>
          </ng-container>
          <ng-container matColumnDef="ip">
            <th mat-header-cell *matHeaderCellDef>IP</th>
            <td mat-cell *matCellDef="let r">{{ r.ipAddress ?? '' }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
        </table>
      </div>
    }
    <mat-paginator [length]="total()" [pageIndex]="page" [pageSize]="size" [pageSizeOptions]="[25, 50, 100]"
      (page)="onPage($event)" aria-label="Audit trail pages" />
  `,
  styles: `
    .details {
      font-size: 0.75rem;
      white-space: pre-wrap;
      word-break: break-word;
      color: var(--aiq-text-muted);
    }
  `,
})
export class AuditTrailComponent implements OnInit {
  private readonly api = inject(ApiService);

  readonly columns = ['occurredAt', 'username', 'action', 'entity', 'outcome', 'details', 'ip'];
  readonly rows = signal<AuditLogEntry[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  action = '';
  username = '';
  page = 0;
  size = 25;

  ngOnInit(): void {
    this.load();
  }

  apply(): void {
    this.page = 0;
    this.load();
  }

  clear(): void {
    this.action = '';
    this.username = '';
    this.apply();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .auditLogs({ action: this.action.trim(), username: this.username.trim(), page: this.page, size: this.size })
      .subscribe({
        next: (res) => {
          this.rows.set(res.content);
          this.total.set(res.totalElements);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(errorMessage(err));
          this.loading.set(false);
        },
      });
  }
}
