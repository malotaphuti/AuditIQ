import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ModelPerformance, RoleName } from '../../core/models';
import { ReportsComponent } from './reports.component';

const PRF = { precision: 0.5, recall: 0.84, f1: 0.62, truePositive: 1633, falsePositive: 1652, falseNegative: 321 };

export const PERFORMANCE: ModelPerformance = {
  modelVersionId: 2, version: 'IF-20261106-010000', trainedAt: '2026-11-06T01:00:00Z', trainedBy: 'auditor',
  trainingRows: 111009, hyperparameters: { n_estimators: 200, max_samples: 256 },
  features: [{ name: 'log_amount', label: 'Transaction amount' }],
  thresholds: { anomaly: 0.55, medium: 0.6, high: 0.65 }, exceptionMinPriority: 'MEDIUM', active: true,
  algorithm: 'IsolationForest', scoredTransactions: 111009,
  scoreNote: 'The anomaly score measures how unusual the transaction is; it is not a probability of fraud.',
  evaluatedAt: '2026-11-06T01:01:00Z',
  scoreDistribution: [{ from: 0.5, to: 0.525, labelled: 10, unlabelled: 500 },
    { from: 0.525, to: 0.55, labelled: 20, unlabelled: 200 }],
  groundTruthAvailable: true, flagged: 3285, labelledAnomalies: 1954,
  confusionMatrix: { truePositive: 1633, falsePositive: 1652, falseNegative: 321, trueNegative: 107403 },
  metrics: { ...PRF, rocAuc: 0.9871, averagePrecision: 0.8321 },
  priorityBands: [{ priority: 'HIGH', from: 0.65, flagged: 979, labelledAnomalies: 972, precision: 0.9928 }],
  recallByType: [{ anomalyType: 'DUPLICATE_PAYMENT', labelled: 250, modelRecall: 0.52, ruleRecall: 1 }],
  comparison: {
    rulesExecuted: true, model: { ...PRF, flagged: 3285 },
    rules: { precision: 0.99, recall: 1, f1: 0.99, truePositive: 1954, falsePositive: 26, falseNegative: 0, flagged: 1980 },
    combined: { precision: 0.54, recall: 1, f1: 0.7, truePositive: 1954, falsePositive: 1665, falseNegative: 0 },
    overlap: { bothDetected: 1633, rulesOnly: 321, modelOnly: 0, neither: 0 },
  },
  falsePositives: { count: 1652, share: 0.5, alsoFlaggedByRules: 13, scoreMedian: 0.567,
    byTransactionType: [{ key: 'TRANSFER', count: 553 }], byChannel: [{ key: 'ONLINE', count: 525 }],
    byTopFactor: [{ key: 'Amount relative to the approval threshold', count: 632 }] },
};

function setup(roles: RoleName[], performance: 'ok' | 'none' = 'ok') {
  const api = jasmine.createSpyObj<ApiService>('ApiService',
    ['modelPerformance', 'modelVersions', 'trainModel', 'detectAnomalies']);
  api.modelPerformance.and.returnValue(performance === 'ok' ? of(PERFORMANCE)
    : throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'No anomaly model' } })));
  api.modelVersions.and.returnValue(of([]));
  api.trainModel.and.returnValue(of({ modelVersionId: 3, version: 'IF-NEW', trainingRows: 111009, durationMs: 9000 }));
  api.detectAnomalies.and.returnValue(of({ modelVersionId: 2, version: 'IF-1', newlyScored: 0, anomalies: 3285,
    byPriority: { HIGH: 979, MEDIUM: 575, LOW: 1731 }, exceptionsCreated: 1554, exceptionMinPriority: 'MEDIUM',
    durationMs: 4000 }));
  TestBed.configureTestingModule({
    imports: [ReportsComponent],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { hasAnyRole: (r: RoleName[]) => r.some((x) => roles.includes(x)) } },
    ],
  });
  const fixture: ComponentFixture<ReportsComponent> = TestBed.createComponent(ReportsComponent);
  fixture.detectChanges();
  return { fixture, api, el: fixture.nativeElement as HTMLElement };
}

describe('ReportsComponent', () => {
  it('reports metrics, the confusion matrix and the rule comparison', () => {
    const { el, fixture } = setup(['AUDIT_MANAGER']);
    const text = el.textContent!;
    expect(text).toContain('IF-20261106-010000');
    expect(text).toContain('0.987');
    expect(text).toContain('107,403');
    expect(text).toContain('not a probability of fraud');
    expect(text).toContain('Model compared with the SQL audit rules');
    expect(text).toContain('False positive analysis');
    expect(text).not.toContain('Train new model');
    const c = fixture.componentInstance;
    expect(c.markers().map((m) => m.value)).toEqual([0.55, 0.6, 0.65]);
    expect(c.labelledBins()[1]).toEqual({ from: 0.525, to: 0.55, count: 20 });
    expect(c.overlap().find((o) => o.key === 'Rules only')?.count).toBe(321);
  });

  it('shows an empty state before any model is trained', () => {
    const { el, fixture } = setup(['AUDITOR'], 'none');
    expect(fixture.componentInstance.noModel()).toBeTrue();
    expect(el.textContent).toContain('No anomaly model trained yet');
  });

  it('lets auditors train and run detection, then reloads', () => {
    const { api, fixture } = setup(['AUDITOR']);
    fixture.componentInstance.train();
    expect(api.trainModel).toHaveBeenCalled();
    fixture.componentInstance.detect();
    expect(api.detectAnomalies).toHaveBeenCalled();
    expect(api.modelPerformance).toHaveBeenCalledTimes(3);
  });
});
