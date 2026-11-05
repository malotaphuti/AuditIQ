import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../../core/api.service';
import { InvestigationDetail } from '../../core/models';
import { EXCEPTION } from '../exceptions/exceptions.component.spec';
import { InvestigationDetailComponent } from './investigation-detail.component';

const AUDITOR = { id: 2, username: 'auditor', fullName: 'Demo Auditor' };

function detail(overrides: Partial<InvestigationDetail['investigation']> = {},
                extra: Partial<InvestigationDetail> = {}): InvestigationDetail {
  return {
    investigation: {
      id: 4, investigationRef: 'INV-000004', title: 'Duplicate payments – TXN2', status: 'UNDER_REVIEW',
      priority: 'HIGH', exceptionId: 7, exceptionRef: 'EXC-000007', ruleId: 'AR-001', ruleName: 'Duplicate payments',
      transactionId: 'TXN2', accountId: 'ACC-000001', amount: 1500, assignedTo: AUDITOR, createdBy: AUDITOR,
      outcome: null, createdAt: '2026-11-05T10:00:00Z', updatedAt: '2026-11-05T10:00:00Z', resolvedAt: null,
      ...overrides,
    },
    version: 3,
    outcomeSummary: null,
    reviewedBy: null,
    reviewedAt: null,
    exception: { ...EXCEPTION, status: 'UNDER_INVESTIGATION' },
    notes: [{ id: 1, author: AUDITOR, note: 'Requested the instruction.', createdAt: '2026-11-05T10:05:00Z' }],
    history: [{ id: 1, fromStatus: null, toStatus: 'NEW', changedBy: AUDITOR, reason: 'Opened',
      changedAt: '2026-11-05T10:00:00Z' }],
    evidence: [{ id: 1, evidenceType: 'TRANSACTION', referenceId: 'TXN2', description: 'Snapshot',
      content: { amount: 1500, approvalStatus: 'NOT_REQUIRED' }, aiGenerated: false, humanReviewStatus: 'NOT_REQUIRED',
      addedBy: AUDITOR, createdAt: '2026-11-05T10:00:00Z' }],
    activity: [],
    allowedTransitions: ['AWAITING_EVIDENCE', 'ESCALATED', 'RESOLVED'],
    outcomes: ['NO_EXCEPTION', 'CONTROL_WEAKNESS'],
    ...extra,
  };
}

function setup(d: InvestigationDetail) {
  const api = jasmine.createSpyObj<ApiService>('ApiService',
    ['investigation', 'assignees', 'changeInvestigationStatus', 'assignInvestigation', 'addInvestigationNote']);
  api.investigation.and.returnValue(of(d));
  api.assignees.and.returnValue(of([AUDITOR, { id: 3, username: 'manager', fullName: 'Demo Manager' }]));
  TestBed.configureTestingModule({
    imports: [InvestigationDetailComponent],
    providers: [provideRouter([]), { provide: ApiService, useValue: api }],
  });
  const fixture: ComponentFixture<InvestigationDetailComponent> = TestBed.createComponent(InvestigationDetailComponent);
  fixture.componentRef.setInput('id', '4');
  fixture.detectChanges();
  return { fixture, api, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('InvestigationDetailComponent', () => {
  it('renders the case, evidence provenance, notes and history', () => {
    const { el, api } = setup(detail());
    expect(api.investigation).toHaveBeenCalledWith(4);
    expect(el.textContent).toContain('INV-000004');
    expect(el.textContent).toContain('Verified data snapshot');
    expect(el.textContent).toContain('Requested the instruction.');
    expect(el.textContent).toContain('Status history');
  });

  it('requires an outcome, a meaningful summary and an assignee to resolve', () => {
    const { c } = setup(detail());
    c.nextStatus = 'RESOLVED';
    expect(c.canSubmitStatus()).toBeFalse();
    c.outcome = 'CONTROL_WEAKNESS';
    c.outcomeSummary = 'too short';
    expect(c.canSubmitStatus()).toBeFalse();
    c.outcomeSummary = 'Invoice obtained; the duplicate was reversed by the bank.';
    expect(c.canSubmitStatus()).toBeTrue();
    TestBed.resetTestingModule();
    const unassigned = setup(detail({ assignedTo: null })).c;
    unassigned.nextStatus = 'RESOLVED';
    unassigned.outcome = 'NO_EXCEPTION';
    unassigned.outcomeSummary = 'Invoice obtained; the duplicate was reversed by the bank.';
    expect(unassigned.canSubmitStatus()).toBeFalse();
  });

  it('requires a reason to escalate', () => {
    const { c } = setup(detail());
    c.nextStatus = 'ESCALATED';
    expect(c.canSubmitStatus()).toBeFalse();
    c.reason = 'Pattern repeats across accounts';
    expect(c.canSubmitStatus()).toBeTrue();
  });

  it('records the outcome with the current version and shows the reviewed result', () => {
    const { c, api, fixture, el } = setup(detail());
    api.changeInvestigationStatus.and.returnValue(of(detail({ status: 'RESOLVED', outcome: 'CONTROL_WEAKNESS' }, {
      version: 4, outcomeSummary: 'Invoice obtained; the duplicate was reversed by the bank.',
      reviewedBy: AUDITOR, reviewedAt: '2026-11-05T11:00:00Z', allowedTransitions: ['UNDER_REVIEW'],
    })));
    c.nextStatus = 'RESOLVED';
    c.outcome = 'CONTROL_WEAKNESS';
    c.outcomeSummary = ' Invoice obtained; the duplicate was reversed by the bank. ';
    c.submitStatus();
    fixture.detectChanges();
    expect(api.changeInvestigationStatus).toHaveBeenCalledWith(4, {
      status: 'RESOLVED', reason: undefined, outcome: 'CONTROL_WEAKNESS',
      outcomeSummary: 'Invoice obtained; the duplicate was reversed by the bank.', version: 3,
    });
    expect(el.textContent).toContain('Human-reviewed outcome');
    expect(el.textContent).toContain('Re-open investigation');
    expect(c.nextStatus).toBe('');
  });

  it('adds notes and reloads after a concurrent modification', () => {
    const { c, api } = setup(detail());
    api.addInvestigationNote.and.returnValue(of(detail()));
    c.note = '  Spoke to the branch.  ';
    c.submitNote();
    expect(api.addInvestigationNote).toHaveBeenCalledWith(4, 'Spoke to the branch.');
    expect(c.note).toBe('');

    api.assignInvestigation.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { message: 'The record was modified by another user. Reload and try again.' } })));
    c.assigneeId = 3;
    c.submitAssignment();
    expect(api.assignInvestigation).toHaveBeenCalledWith(4, 3, 3);
    expect(c.actionError()).toContain('modified by another user');
    expect(api.investigation).toHaveBeenCalledTimes(2);
  });
});
