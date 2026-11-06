import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { AuditRule, ExceptionRow, ExceptionSummary, RoleName } from '../../core/models';
import { ExceptionsComponent } from './exceptions.component';

export const EXCEPTION: ExceptionRow = {
  id: 7, exceptionRef: 'EXC-000007', detectionSource: 'RULE', ruleId: 'AR-001', ruleName: 'Duplicate payments',
  transactionId: 'TXN2', accountId: 'ACC-000001', transactionDate: '2026-05-10T08:20:00Z', amount: 1500,
  transactionType: 'TRANSFER', severity: 'HIGH', riskCategory: 'PAYMENT_INTEGRITY', status: 'OPEN',
  reason: 'Same account, amount and business date as transaction TXN1.', detectedAt: '2026-11-05T10:00:00Z',
  anomalyScore: 0.612, anomalyPriority: 'MEDIUM', investigation: null,
};

const SUMMARY: ExceptionSummary = {
  total: 12, open: 10, underInvestigation: 1, closed: 1,
  bySeverity: { CRITICAL: 1, HIGH: 6, MEDIUM: 5, LOW: 0 },
  byRiskCategory: [{ key: 'PAYMENT_INTEGRITY', label: 'PAYMENT_INTEGRITY', count: 12 }],
  byRule: [{ key: 'AR-001', label: 'Duplicate payments', count: 12 }],
  bySource: { RULE: 12, ML: 0 },
};

const RULE: AuditRule = {
  ruleId: 'AR-001', ruleName: 'Duplicate payments', description: 'Same day repeats', detectionLogic: 'Group by…',
  riskCategory: 'PAYMENT_INTEGRITY', severity: 'MEDIUM', enabled: true, version: 2, lastExecutedAt: null,
  updatedAt: '', updatedBy: null, exceptionsTotal: 12, exceptionsOpen: 10, lastExecution: null,
  thresholds: { min_amount: 100, match_merchant: false, statuses: ['COMPLETED', 'PENDING'] },
  thresholdSchema: {
    min_amount: { type: 'number', label: 'Minimum amount (ZAR)' },
    match_merchant: { type: 'boolean', label: 'Also require the same merchant' },
    statuses: { type: 'list', label: 'Transaction statuses' },
  },
};

function setup(roles: RoleName[], queryParams: Record<string, string> = {}) {
  const api = jasmine.createSpyObj<ApiService>('ApiService',
    ['exceptions', 'exceptionSummary', 'auditRules', 'executeAuditRules', 'detectAnomalies']);
  api.detectAnomalies.and.returnValue(of({
    modelVersionId: 1, version: 'IF-1', newlyScored: 0, anomalies: 30, byPriority: { HIGH: 10, MEDIUM: 5, LOW: 15 },
    exceptionsCreated: 15, exceptionMinPriority: 'MEDIUM', durationMs: 900,
  }));
  api.exceptions.and.returnValue(of({ content: [EXCEPTION], page: 0, size: 25, totalElements: 1, totalPages: 1 }));
  api.exceptionSummary.and.returnValue(of(SUMMARY));
  api.auditRules.and.returnValue(of([RULE]));
  api.executeAuditRules.and.returnValue(of({
    batchRef: 'RULES-1', durationMs: 900, exceptionsCreated: 3, matchesTotal: 15,
    results: [{ ruleId: 'AR-001', ruleName: 'Duplicate payments', executionId: 1, status: 'SUCCESS',
      matchesTotal: 15, exceptionsCreated: 3, durationMs: 300, error: null }],
  }));
  TestBed.configureTestingModule({
    imports: [ExceptionsComponent],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { hasAnyRole: (r: RoleName[]) => r.some((x) => roles.includes(x)) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } },
    ],
  });
  const fixture: ComponentFixture<ExceptionsComponent> = TestBed.createComponent(ExceptionsComponent);
  fixture.detectChanges();
  return { fixture, api, el: fixture.nativeElement as HTMLElement };
}

describe('ExceptionsComponent', () => {
  it('loads the summary, rule catalogue and first page', () => {
    const { api, el, fixture } = setup(['AUDITOR']);
    expect(api.exceptionSummary).toHaveBeenCalled();
    expect(api.auditRules).toHaveBeenCalled();
    expect(api.exceptions).toHaveBeenCalledWith(jasmine.objectContaining({ page: 0, sort: 'detectedAt' }));
    expect(el.textContent).toContain('EXC-000007');
    expect(el.textContent).toContain('not conclusions of wrongdoing');
    expect(fixture.componentInstance.byRule()[0]).toEqual({ key: 'AR-001 Duplicate payments', count: 12, value: 0 });
    expect(fixture.componentInstance.bySeverity().map((b) => b.key)).toEqual(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']);
  });

  it('lets auditors run the rules and refreshes the results', () => {
    const { api, el, fixture } = setup(['AUDITOR']);
    const button = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Run audit rules'));
    expect(button).toBeDefined();
    button!.click();
    fixture.detectChanges();
    expect(api.executeAuditRules).toHaveBeenCalledWith();
    expect(el.textContent).toContain('Execution RULES-1');
    expect(api.exceptionSummary).toHaveBeenCalledTimes(2);
    expect(api.exceptions).toHaveBeenCalledTimes(2);
  });

  it('hides the run buttons from audit managers', () => {
    const { el } = setup(['AUDIT_MANAGER']);
    expect(el.textContent).not.toContain('Run audit rules');
    expect(el.textContent).not.toContain('Run anomaly detection');
  });

  it('runs anomaly detection, shows model scores and filters by detector', () => {
    const { api, el, fixture } = setup(['AUDITOR']);
    expect(el.textContent).toContain('0.612');
    fixture.componentInstance.runDetection();
    expect(api.detectAnomalies).toHaveBeenCalled();
    expect(api.exceptions).toHaveBeenCalledTimes(2);
    const c = fixture.componentInstance;
    c.filters = { ...c.filters, source: 'ML' };
    c.onSort({ active: 'anomalyScore', direction: 'desc' });
    expect(api.exceptions.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ source: 'ML', sort: 'anomalyScore', direction: 'desc' }));
  });

  it('sends only populated filters and accepts filters from the URL', () => {
    const { api, fixture } = setup(['AUDITOR'], { status: 'OPEN' });
    expect(api.exceptions.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({ status: 'OPEN' }));
    const c = fixture.componentInstance;
    c.filters = { ...c.filters, severity: 'HIGH', accountId: ' ACC-000001 ' };
    c.apply();
    const q = api.exceptions.calls.mostRecent().args[0];
    expect(q).toEqual(jasmine.objectContaining({ severity: 'HIGH', accountId: 'ACC-000001', status: 'OPEN' }));
    expect(q.ruleId).toBeUndefined();
  });

  it('describes rule thresholds in plain language', () => {
    const { fixture } = setup(['AUDITOR']);
    expect(fixture.componentInstance.thresholdEntries(RULE)).toEqual([
      { label: 'Minimum amount (ZAR)', value: '100' },
      { label: 'Also require the same merchant', value: 'No' },
      { label: 'Transaction statuses', value: 'COMPLETED, PENDING' },
    ]);
  });

  it('opens the exception detail page', () => {
    const { fixture } = setup(['AUDITOR']);
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    fixture.componentInstance.open(EXCEPTION);
    expect(router.navigate).toHaveBeenCalledWith(['/exceptions', 7]);
  });
});
