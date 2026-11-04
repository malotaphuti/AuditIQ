import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Overview } from '../../core/models';
import { OverviewComponent } from './overview.component';

const EMPTY: Overview = {
  generatedAt: '2026-01-01T10:00:00Z',
  transactions: { total: 0, totalValue: 0 },
  exceptions: { total: 0, highPriority: 0, open: 0, exceptionRatePercent: 0, bySeverity: {}, bySource: { RULE: 0, ML: 0 } },
  investigationsByStatus: { NEW: 2, UNDER_REVIEW: 1, AWAITING_EVIDENCE: 0, ESCALATED: 0, RESOLVED: 1 },
  pipeline: { totalRuns: 0, successfulRuns: 0 },
  transactionTrend: [],
  riskDistribution: [],
  recentExceptions: [],
  recentActivity: [
    { id: 1, occurredAt: '2026-01-01T09:00:00Z', username: 'auditor', action: 'LOGIN_SUCCESS', outcome: 'SUCCESS', details: {} },
  ],
};

describe('OverviewComponent', () => {
  let fixture: ComponentFixture<OverviewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OverviewComponent],
      providers: [{ provide: ApiService, useValue: { overview: () => of(EMPTY) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(OverviewComponent);
    fixture.detectChanges();
  });

  it('renders values from the API rather than placeholders', () => {
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('No transactions loaded');
    expect(text).toContain('No runs yet');
    expect(text).toContain('Login success');
  });

  it('computes investigation status proportions', () => {
    const rows = fixture.componentInstance.investigationRows();
    expect(fixture.componentInstance.investigationTotal()).toBe(4);
    expect(rows.find((r) => r.status === 'NEW')?.percent).toBe(50);
  });

  it('shows empty states when there is no analytical data', () => {
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('No transaction data yet');
    expect(text).toContain('No exceptions recorded');
  });
});
