import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { InvestigationRow, InvestigationSummary } from '../../core/models';
import { InvestigationsComponent } from './investigations.component';

const ROW: InvestigationRow = {
  id: 4, investigationRef: 'INV-000004', title: 'Duplicate payments – TXN2', status: 'NEW', priority: 'HIGH',
  exceptionId: 7, exceptionRef: 'EXC-000007', ruleId: 'AR-001', ruleName: 'Duplicate payments', transactionId: 'TXN2',
  accountId: 'ACC-000001', amount: 1500, assignedTo: null,
  createdBy: { id: 2, username: 'auditor', fullName: 'Demo Auditor' }, outcome: null,
  createdAt: '2026-11-05T10:00:00Z', updatedAt: '2026-11-05T10:00:00Z', resolvedAt: null,
};
const SUMMARY: InvestigationSummary = {
  byStatus: { NEW: 1, UNDER_REVIEW: 0, AWAITING_EVIDENCE: 0, ESCALATED: 0, RESOLVED: 2 },
  open: 1, assignedToMe: 0, unassigned: 1, exceptionsAwaitingReview: 40, averageResolutionHours: 60,
};

describe('InvestigationsComponent', () => {
  let fixture: ComponentFixture<InvestigationsComponent>;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['investigations', 'investigationSummary']);
    api.investigations.and.returnValue(of({ content: [ROW], page: 0, size: 25, totalElements: 1, totalPages: 1 }));
    api.investigationSummary.and.returnValue(of(SUMMARY));
    TestBed.configureTestingModule({
      imports: [InvestigationsComponent],
      providers: [provideRouter([]), { provide: ApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(InvestigationsComponent);
    fixture.detectChanges();
  });

  it('shows open investigations by default with workload figures', () => {
    expect(api.investigations).toHaveBeenCalledWith(jasmine.objectContaining({ status: 'OPEN', page: 0 }));
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('INV-000004');
    expect(text).toContain('Unassigned');
    expect(text).toContain('2.5 days');
    expect(fixture.componentInstance.totalInvestigations()).toBe(3);
  });

  it('filters by assignee and resets to the default view', () => {
    const c = fixture.componentInstance;
    c.filters = { ...c.filters, assignee: 'me', status: '' };
    c.apply();
    const q = api.investigations.calls.mostRecent().args[0];
    expect(q.assignee).toBe('me');
    expect(q.status).toBeUndefined();
    expect(c.isDefault()).toBeFalse();
    c.reset();
    expect(api.investigations.calls.mostRecent().args[0].status).toBe('OPEN');
  });

  it('formats resolution time and opens a case', () => {
    const c = fixture.componentInstance;
    expect(c.formatHours(null)).toBe('—');
    expect(c.formatHours(5.25)).toBe('5.3 h');
    expect(c.formatHours(0.5)).toBe('30 min');
    expect(c.formatHours(0)).toBe('under 6 min');
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    c.open(ROW);
    expect(router.navigate).toHaveBeenCalledWith(['/investigations', 4]);
  });
});
