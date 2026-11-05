import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuditRule } from '../../core/models';
import { AuditRulesAdminComponent } from './audit-rules-admin.component';

const RULE: AuditRule = {
  ruleId: 'AR-004', ruleName: 'High-frequency transactions', description: '', detectionLogic: '',
  riskCategory: 'TRANSACTION_MONITORING', severity: 'MEDIUM', enabled: true, version: 1, lastExecutedAt: null,
  updatedAt: '', updatedBy: null, exceptionsTotal: 0, exceptionsOpen: 0, lastExecution: null,
  thresholds: { window_minutes: 60, min_transactions: 10 },
  thresholdSchema: {
    window_minutes: { type: 'integer', min: 1, max: 1440, label: 'Time window (minutes)' },
    min_transactions: { type: 'integer', min: 2, max: 500, label: 'Minimum transactions in window' },
  },
};

describe('AuditRulesAdminComponent', () => {
  let fixture: ComponentFixture<AuditRulesAdminComponent>;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['auditRules', 'updateAuditRule']);
    api.auditRules.and.returnValue(of([RULE]));
    TestBed.configureTestingModule({
      imports: [AuditRulesAdminComponent],
      providers: [{ provide: ApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(AuditRulesAdminComponent);
    fixture.detectChanges();
  });

  it('builds an editable draft per rule without mutating the loaded rule', () => {
    const c = fixture.componentInstance;
    c.drafts['AR-004'].thresholds['min_transactions'] = 15;
    expect(RULE.thresholds['min_transactions']).toBe(10);
    c.reset(RULE);
    expect(c.drafts['AR-004'].thresholds['min_transactions']).toBe(10);
    expect(c.schemaEntries(RULE).map((e) => e.key)).toEqual(['window_minutes', 'min_transactions']);
  });

  it('saves the draft and adopts the new version', () => {
    const c = fixture.componentInstance;
    api.updateAuditRule.and.returnValue(of({ ...RULE, version: 2, thresholds: { window_minutes: 30, min_transactions: 10 } }));
    c.drafts['AR-004'].thresholds['window_minutes'] = 30;
    c.save(RULE);
    expect(api.updateAuditRule).toHaveBeenCalledWith('AR-004', {
      thresholds: { window_minutes: 30, min_transactions: 10 }, enabled: true, severity: 'MEDIUM',
    });
    expect(c.rules()[0].version).toBe(2);
  });
});
