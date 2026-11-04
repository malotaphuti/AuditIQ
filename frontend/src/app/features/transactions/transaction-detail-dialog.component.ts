import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { TransactionDetail } from '../../core/models';
import { HumanizePipe } from '../../shared/humanize.pipe';

@Component({
  selector: 'app-transaction-detail-dialog',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, MatDialogModule, MatButtonModule, MatProgressBarModule, HumanizePipe],
  template: `
    <h2 mat-dialog-title>Transaction {{ id }}</h2>
    <mat-dialog-content>
      @if (detail(); as d) {
        <p class="source-label">Verified transaction record · curated layer</p>
        <div class="grid">
          <section>
            <h3>Transaction</h3>
            <dl>
              <dt>Date</dt><dd>{{ d.transaction.transactionDate | date: 'medium' }}</dd>
              <dt>Amount</dt><dd class="strong">{{ d.transaction.amount | currency: 'ZAR' : 'symbol-narrow' }}</dd>
              @if (d.lineage.originalCurrency && d.lineage.originalCurrency !== 'ZAR') {
                <dt>Original</dt><dd>{{ d.lineage.originalAmount | number: '1.2-2' }} {{ d.lineage.originalCurrency }}</dd>
              }
              <dt>Type</dt><dd>{{ d.transaction.transactionType | humanize }}</dd>
              <dt>Channel</dt><dd>{{ d.transaction.channel }}</dd>
              <dt>Location</dt><dd>{{ d.transaction.location ?? '—' }}</dd>
              <dt>Status</dt><dd>{{ d.transaction.status | humanize }}</dd>
              <dt>Approval</dt>
              <dd>{{ d.transaction.approvalStatus | humanize }}
                <span class="muted small">(threshold {{ d.transaction.approvalThreshold | currency: 'ZAR' : 'symbol-narrow' : '1.0-0' }})</span></dd>
            </dl>
          </section>
          <section>
            <h3>Account</h3>
            <dl>
              <dt>Account</dt><dd>{{ d.account.accountId }} · {{ d.account.accountType | humanize }}</dd>
              <dt>Customer</dt><dd>{{ d.account.customerId }}</dd>
              <dt>Status</dt><dd>{{ d.account.status | humanize }}</dd>
              <dt>Branch</dt><dd>{{ d.account.branch ?? '—' }}</dd>
              <dt>Opened</dt><dd>{{ d.account.openedDate | date: 'mediumDate' }}</dd>
              <dt>Transactions</dt><dd>{{ d.account.transactionCount | number }} (avg {{ d.account.averageAmount | currency: 'ZAR' : 'symbol-narrow' : '1.0-0' }})</dd>
              <dt>Activity</dt><dd>{{ d.account.firstTransactionAt | date: 'mediumDate' }} – {{ d.account.lastTransactionAt | date: 'mediumDate' }}</dd>
            </dl>
            @if (d.merchant; as m) {
              <h3>Merchant</h3>
              <dl>
                <dt>Name</dt><dd>{{ m.merchantName }} ({{ m.merchantId }})</dd>
                <dt>Category</dt><dd>{{ m.category | humanize }}{{ m.vendor ? ' · vendor' : '' }}</dd>
                <dt>City</dt><dd>{{ m.city ?? '—' }}</dd>
              </dl>
            }
          </section>
        </div>

        <h3>Audit exceptions</h3>
        @if (d.exceptions.length === 0) {
          <p class="muted small">No potential exceptions are linked to this transaction.</p>
        } @else {
          <ul class="exceptions">
            @for (e of d.exceptions; track e.id) {
              <li>
                <span class="chip" [class]="'sev-' + e.severity">{{ e.severity | humanize }}</span>
                {{ e.exceptionRef }} · {{ e.riskCategory | humanize }} ·
                {{ e.detectionSource === 'RULE' ? e.ruleId : 'Anomaly model' }} · {{ e.status | humanize }}
              </li>
            }
          </ul>
        }

        <h3>Lineage</h3>
        <p class="muted small">
          Loaded {{ d.lineage.loadedAt | date: 'medium' }}
          @if (d.lineage.runRef) { by pipeline run {{ d.lineage.runRef }} }
        </p>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        <mat-progress-bar mode="indeterminate" aria-label="Loading transaction" />
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .source-label {
      font-size: 0.75rem; color: var(--aiq-green); font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.04em; margin: 0 0 8px;
    }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    h3 { font-size: 0.9rem; margin: 16px 0 6px; color: var(--aiq-text); }
    dl { display: grid; grid-template-columns: 110px 1fr; gap: 4px 12px; margin: 0; font-size: 0.875rem; }
    dt { color: var(--aiq-text-muted); }
    dd { margin: 0; }
    .exceptions { margin: 0; padding-left: 18px; font-size: 0.875rem; }
    .error { color: var(--aiq-red); }
    @media (max-width: 699px) { .grid { grid-template-columns: 1fr; } }
  `,
})
export class TransactionDetailDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly id = inject<string>(MAT_DIALOG_DATA);
  readonly detail = signal<TransactionDetail | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.transaction(this.id).subscribe({
      next: (d) => this.detail.set(d),
      error: (err) => this.error.set(errorMessage(err, 'Could not load the transaction.')),
    });
  }
}
