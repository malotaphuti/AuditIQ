import { ComponentFixture, TestBed, fakeAsync, flush } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { TransactionAnalytics, TransactionRow } from '../../core/models';
import { TransactionsComponent } from './transactions.component';

const ROW: TransactionRow = {
  transactionId: 'TXN1', accountId: 'ACC-000001', customerId: 'CUS-1', transactionDate: '2026-03-01T08:00:00Z',
  amount: 120.5, currency: 'ZAR', transactionType: 'PURCHASE', channel: 'POS', merchantName: 'Protea Grocer',
  merchantCategory: 'GROCERIES', accountType: 'CHEQUE', status: 'COMPLETED', approvalStatus: 'NOT_REQUIRED',
};
const ANALYTICS: TransactionAnalytics = {
  totals: { count: 1, value: 120.5, averageValue: 120.5, accounts: 1, merchants: 1 },
  monthly: [{ key: '2026-03', count: 1, value: 120.5 }],
  byCategory: [{ key: 'GROCERIES', count: 1, value: 120.5 }],
  byChannel: [{ key: 'POS', count: 1, value: 120.5 }],
  byType: [], byStatus: [], byLocation: [],
};

describe('TransactionsComponent', () => {
  let fixture: ComponentFixture<TransactionsComponent>;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['transactions', 'transactionAnalytics', 'transaction']);
    api.transactions.and.returnValue(of({ content: [ROW], page: 0, size: 25, totalElements: 1, totalPages: 1 }));
    api.transactionAnalytics.and.returnValue(of(ANALYTICS));
    TestBed.configureTestingModule({
      imports: [TransactionsComponent],
      providers: [{ provide: ApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(TransactionsComponent);
    fixture.detectChanges();
  });

  it('loads analytics and the first page on init', () => {
    expect(api.transactionAnalytics).toHaveBeenCalled();
    expect(api.transactions).toHaveBeenCalledWith(jasmine.objectContaining({
      page: 0, size: 25, sort: 'transactionDate', direction: 'desc',
    }));
    expect(fixture.nativeElement.textContent).toContain('Protea Grocer');
    expect(fixture.componentInstance.monthly()).toEqual([{ key: '2026-03', value: 1, secondary: 120.5 }]);
  });

  it('sends only populated filters and resets to the first page', () => {
    const c = fixture.componentInstance;
    c.page = 3;
    c.filters = { ...c.filters, accountId: ' ACC-000001 ', channel: 'ONLINE', minAmount: 100 };
    c.apply();
    const q = api.transactions.calls.mostRecent().args[0];
    expect(q).toEqual(jasmine.objectContaining({ accountId: 'ACC-000001', channel: 'ONLINE', minAmount: 100, page: 0 }));
    expect(q.type).toBeUndefined();
    expect(q.q).toBeUndefined();
  });

  it('maps table sorting to whitelisted API sort keys', () => {
    const c = fixture.componentInstance;
    c.onSort({ active: 'amount', direction: 'asc' });
    expect(api.transactions.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({ sort: 'amount', direction: 'asc' }));
    c.onSort({ active: 'amount', direction: '' });
    expect(api.transactions.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ sort: 'transactionDate', direction: 'desc' }));
  });

  it('clearing filters restores the unfiltered query', () => {
    const c = fixture.componentInstance;
    c.filters = { ...c.filters, status: 'FAILED' };
    expect(c.hasFilters()).toBeTrue();
    c.clear();
    expect(c.hasFilters()).toBeFalse();
    expect(api.transactions.calls.mostRecent().args[0].status).toBeUndefined();
  });

  it('opens the detail dialog for a row', fakeAsync(() => {
    api.transaction.and.returnValue(of());
    fixture.componentInstance.open(ROW);
    fixture.detectChanges();
    flush();
    fixture.detectChanges();
    expect(document.body.textContent).toContain('Transaction TXN1');
    expect(api.transaction).toHaveBeenCalledWith('TXN1');
    TestBed.inject(MatDialog).closeAll();
    flush();
  }));
});
