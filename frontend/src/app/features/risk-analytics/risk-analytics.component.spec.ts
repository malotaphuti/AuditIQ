import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { RiskDashboard, RoleName } from '../../core/models';
import { RiskAnalyticsComponent } from './risk-analytics.component';

const DASHBOARD: RiskDashboard = {
  generatedAt: '2026-11-06T00:00:00Z', from: '2025-11-01', to: '2026-10-31',
  dataFrom: '2025-11-01', dataTo: '2026-10-31',
  executive: {
    transactions: 111009, totalValue: 1_234_567_890, potentialExceptions: 3647, highPriority: 2390,
    flaggedTransactions: 3100, exceptionRatePercent: 2.79, awaitingReview: 3646, openInvestigations: 0,
    pipeline: { runs: 1, successfulRuns: 1, lastStatus: 'SUCCESS', lastRunAt: '2026-11-05T10:00:00Z',
      lastValidRatePercent: 98.77 },
  },
  transactions: {
    daily: [{ date: '2026-10-30', count: 300, value: 900000, flagged: 9 }],
    monthly: [{ key: '2026-10', count: 9000, value: 27_000_000 }],
    byCategory: [{ key: 'GROCERIES', count: 20000, value: 1_000_000 }],
    byChannel: [{ key: 'POS', count: 40000, value: 5_000_000 }],
    byType: [], byLocation: [{ key: 'Durban', count: 9000, value: 1 }], byHour: [],
  },
  risk: {
    bySeverity: { CRITICAL: 86, HIGH: 2304, MEDIUM: 1257, LOW: 0 },
    byRiskCategory: [{ key: 'BEHAVIOURAL_ANOMALY', count: 1554, value: 1 }],
    byDetector: [
      { key: 'AR-001', name: 'Duplicate payments', type: 'Audit rule', exceptions: 250, highPriority: 10 },
      { key: 'ML', name: 'Anomaly model (Isolation Forest)', type: 'Anomaly model', exceptions: 1554, highPriority: 979 },
    ],
    byStatus: { OPEN: 3646, UNDER_INVESTIGATION: 0, CLOSED: 1 },
    monthlyTrend: [{ month: '2026-10', transactions: 9000, flaggedTransactions: 250, flaggedRatePercent: 2.78,
      ruleFindings: 170, modelFindings: 120 }],
    ruleVsModel: { rulesOnly: 500, modelOnly: 50, both: 1500 },
    topAccounts: [{ accountId: 'ACC-001989', accountType: 'SAVINGS', branch: 'Polokwane', exceptions: 57,
      ruleFindings: 29, modelFindings: 28, highPriority: 53, detectors: 2, flaggedAmount: 11425.13,
      maxAnomalyScore: 0.731, awaitingReview: 57, openInvestigations: 0 }],
  },
  investigations: {
    byStatus: { NEW: 0, UNDER_REVIEW: 0, AWAITING_EVIDENCE: 0, ESCALATED: 0, RESOLVED: 1 },
    open: 0, inProgress: 0, resolved: 1, averageResolutionHours: 0.01, oldestOpenAgeHours: null,
    awaitingReview: 3646, byOutcome: [{ key: 'CONTROL_WEAKNESS', count: 1, value: null }], openByAssignee: [],
  },
};

function setup(roles: RoleName[]) {
  const api = jasmine.createSpyObj<ApiService>('ApiService', ['riskDashboard', 'analyticsDatasets', 'exportDataset']);
  api.riskDashboard.and.returnValue(of(DASHBOARD));
  api.analyticsDatasets.and.returnValue(of(['fact_exceptions', 'dim_date']));
  TestBed.configureTestingModule({
    imports: [RiskAnalyticsComponent],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { hasAnyRole: (r: RoleName[]) => r.some((x) => roles.includes(x)) } },
    ],
  });
  const fixture: ComponentFixture<RiskAnalyticsComponent> = TestBed.createComponent(RiskAnalyticsComponent);
  fixture.detectChanges();
  return { fixture, api, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('RiskAnalyticsComponent', () => {
  it('renders the executive overview from the API', () => {
    const { el, api, c } = setup(['AUDIT_MANAGER']);
    expect(api.riskDashboard).toHaveBeenCalledWith(undefined, undefined);
    expect(el.textContent).toContain('111,009');
    expect(el.textContent).toContain('2.79%');
    expect(c.from).toBe('2025-11-01');
    expect(c.flaggedRate()).toEqual([{ key: '2026-10', value: 2.78, secondary: 250 }]);
  });

  it('derives chart series for detectors, severity and overlap', () => {
    const { c } = setup(['AUDITOR']);
    expect(c.byDetector().map((d) => d.key)).toEqual(['AR-001 Duplicate payments', 'Anomaly model']);
    expect(c.bySeverity().map((s) => s.count)).toEqual([86, 2304, 1257, 0]);
    expect(c.overlap().find((o) => o.key === 'Both rules and model')?.count).toBe(1500);
    expect(c.investigationStatus().find((s) => s.key === 'RESOLVED')?.count).toBe(1);
  });

  it('applies date presets relative to the latest data', () => {
    const { c, api } = setup(['AUDITOR']);
    c.preset(30);
    expect(api.riskDashboard).toHaveBeenCalledWith('2026-10-02', '2026-10-31');
    c.preset('all');
    expect(api.riskDashboard).toHaveBeenCalledWith('2025-11-01', '2026-10-31');
  });

  it('offers dataset exports to auditors but not administrators', () => {
    let { el, api } = setup(['AUDITOR']);
    expect(api.analyticsDatasets).toHaveBeenCalled();
    expect(el.textContent).toContain('fact_exceptions');
    TestBed.resetTestingModule();
    ({ el, api } = setup(['ADMIN']));
    expect(api.analyticsDatasets).not.toHaveBeenCalled();
    expect(el.textContent).not.toContain('Analytical datasets');
  });

  it('renders when the API omits null fields (Jackson non_null)', () => {
    const sparse = structuredClone(DASHBOARD) as unknown as Record<string, Record<string, unknown>>;
    delete sparse['investigations']['oldestOpenAgeHours'];
    delete sparse['investigations']['averageResolutionHours'];
    TestBed.configureTestingModule({
      imports: [RiskAnalyticsComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: {
          riskDashboard: () => of(sparse), analyticsDatasets: () => of([]), exportDataset: () => of(new Blob()) } },
        { provide: AuthService, useValue: { hasAnyRole: () => true } },
      ],
    });
    const fixture = TestBed.createComponent(RiskAnalyticsComponent);
    expect(() => fixture.detectChanges()).not.toThrow();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Investigation monitoring');
  });

  it('formats durations', () => {
    const { c } = setup(['AUDITOR']);
    expect(c.hours(null)).toBe('—');
    expect(c.hours(undefined as unknown as null)).toBe('—');
    expect(c.hours(0.01)).toBe('1 min');
    expect(c.hours(10)).toBe('10.0 h');
    expect(c.hours(72)).toBe('3.0 days');
  });
});
