import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ExceptionDetail, TransactionRow } from '../../core/models';
import { ExceptionDetailComponent } from './exception-detail.component';
import { EXCEPTION } from './exceptions.component.spec';

const TX: TransactionRow = {
  transactionId: 'TXN2', accountId: 'ACC-000001', customerId: 'CUS-1', transactionDate: '2026-05-10T08:20:00Z',
  amount: 1500, currency: 'ZAR', transactionType: 'TRANSFER', channel: 'ONLINE', accountType: 'CHEQUE',
  status: 'COMPLETED', approvalStatus: 'NOT_REQUIRED', approvalThreshold: 50000,
};

function detail(overrides: Partial<ExceptionDetail> = {}): ExceptionDetail {
  return {
    exception: EXCEPTION,
    details: { matchedTransactionId: 'TXN1', minutesApart: 20, ruleVersion: 1, thresholds: { min_amount: 100 },
      relatedTransactionIds: ['TXN1'] },
    rule: { ruleId: 'AR-001', ruleName: 'Duplicate payments', description: 'Repeated payments',
      detectionLogic: 'Group by account, amount and date', riskCategory: 'PAYMENT_INTEGRITY', currentVersion: 2 },
    execution: { id: 3, batchRef: 'RULES-1', executedBy: 'auditor', startedAt: '2026-11-05T10:00:00Z' },
    transaction: TX,
    linkedTransactions: [{ transaction: { ...TX, transactionId: 'TXN1' }, relation: 'LINKED_BY_RULE', exceptionRefs: [] }],
    accountActivity: [],
    otherFindings: [],
    investigations: [],
    ...overrides,
  };
}

function setup(d: ExceptionDetail, id = '7') {
  const api = jasmine.createSpyObj<ApiService>('ApiService', ['exception', 'assignees', 'createInvestigation']);
  api.exception.and.returnValue(of(d));
  TestBed.configureTestingModule({
    imports: [ExceptionDetailComponent],
    providers: [provideRouter([]), { provide: ApiService, useValue: api }],
  });
  const fixture: ComponentFixture<ExceptionDetailComponent> = TestBed.createComponent(ExceptionDetailComponent);
  fixture.componentRef.setInput('id', id);
  fixture.detectChanges();
  return { fixture, api, el: fixture.nativeElement as HTMLElement };
}

describe('ExceptionDetailComponent', () => {
  it('separates the analytical finding from verified transaction data', () => {
    const { el, api } = setup(detail());
    expect(api.exception).toHaveBeenCalledWith(7);
    expect(el.textContent).toContain('potential exception');
    expect(el.textContent).toContain('Analytical finding');
    expect(el.textContent).toContain('Verified transaction data');
    expect(el.textContent).toContain('Same account, amount and business date');
    expect(el.textContent).toContain('Transactions linked by the rule');
    expect(el.textContent).toContain('current version 2');
  });

  it('shows evidence facts but not internal bookkeeping keys', () => {
    const { fixture } = setup(detail());
    const labels = fixture.componentInstance.facts().map((f) => f.label);
    expect(labels).toEqual(['Matched transaction id', 'Minutes apart']);
    expect(fixture.componentInstance.thresholds()).toEqual([{ label: 'Min amount', value: '100' }]);
  });

  it('offers to open an investigation only when none exists', () => {
    let { el } = setup(detail());
    expect(el.textContent).toContain('Open investigation');
    TestBed.resetTestingModule();
    ({ el } = setup(detail({ investigations: [{ id: 4, investigationRef: 'INV-000004', status: 'NEW',
      assignedTo: null }] })));
    expect(el.textContent).not.toContain('Open investigation');
    expect(el.textContent).toContain('View INV-000004');
  });

  it('rejects malformed identifiers without calling the API', () => {
    const { el, api } = setup(detail(), 'abc');
    expect(api.exception).not.toHaveBeenCalled();
    expect(el.textContent).toContain('Invalid exception reference');
  });
});
