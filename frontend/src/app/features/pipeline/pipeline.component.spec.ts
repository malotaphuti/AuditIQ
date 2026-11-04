import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { PipelineRunDetail, PipelineRunSummary, RoleName } from '../../core/models';
import { PipelineComponent } from './pipeline.component';

const RUN: PipelineRunSummary = {
  id: 5, runRef: 'RUN-1', status: 'SUCCESS', triggerType: 'MANUAL', triggeredBy: 'auditor', source: 'gen',
  startedAt: '2026-11-01T10:00:00Z', durationMs: 42000, totalRecords: 1000, validRecords: 980, rejectedRecords: 15,
  duplicateRecords: 5,
};
const DETAIL: PipelineRunDetail = {
  run: RUN,
  parameters: { seed: 1 },
  steps: [
    { name: 'extract', status: 'SUCCESS', startedAt: '', durationMs: 900, rows: 1000 },
    { name: 'validate_and_standardise', status: 'SUCCESS', startedAt: '', durationMs: 500, rows: 1000,
      details: { standardisations: { dates_standardised: 300 } } },
  ],
  qualityReport: {
    pipelineRunId: 5, runRef: 'RUN-1', totalRecords: 1000, validRecords: 980, rejectedRecords: 15,
    duplicateRecords: 5, missingValueTotal: 9, missingValues: { account_id: 9 },
    rejectionReasons: { MISSING_ACCOUNT_ID: 9, NEGATIVE_AMOUNT: 6 }, processingDurationMs: 42000,
    status: 'SUCCESS', createdAt: '', validRatePercent: 98,
  },
};

function setup(roles: RoleName[], runs: PipelineRunSummary[] = [RUN]) {
  const api = jasmine.createSpyObj<ApiService>('ApiService',
    ['pipelineRuns', 'pipelineRun', 'pipelineSources', 'runPipeline']);
  api.pipelineRuns.and.returnValue(of({ content: runs, page: 0, size: 10, totalElements: runs.length, totalPages: 1 }));
  api.pipelineRun.and.returnValue(of(DETAIL));
  api.pipelineSources.and.returnValue(of({ folders: ['sample'] }));
  api.runPipeline.and.returnValue(of({ runId: 6, runRef: 'RUN-2', status: 'RUNNING' }));
  TestBed.configureTestingModule({
    imports: [PipelineComponent],
    providers: [
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { hasAnyRole: (r: RoleName[]) => r.some((x) => roles.includes(x)) } },
    ],
  });
  const fixture: ComponentFixture<PipelineComponent> = TestBed.createComponent(PipelineComponent);
  fixture.detectChanges();
  return { fixture, api };
}

describe('PipelineComponent', () => {
  it('selects the latest run and renders its quality report', () => {
    const { fixture } = setup(['AUDITOR']);
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Data quality report');
    expect(text).toContain('98.00%');
    expect(text).toContain('Missing account ID');
    expect(fixture.componentInstance.standardisations()[0]).toEqual({ key: 'dates_standardised', count: 300, value: 300 });
  });

  it('starts a generated run with the chosen parameters', () => {
    const { fixture, api } = setup(['AUDITOR']);
    const c = fixture.componentInstance;
    c.transactionCount = 5000;
    c.seed = 9;
    c.run();
    expect(api.runPipeline).toHaveBeenCalledWith({ source: 'GENERATED', transactionCount: 5000, seed: 9 });
    expect(api.pipelineRun).toHaveBeenCalledWith(6);
  });

  it('starts a CSV run for the selected folder', () => {
    const { fixture, api } = setup(['ADMIN']);
    const c = fixture.componentInstance;
    c.source = 'CSV';
    c.folder = 'sample';
    c.run();
    expect(api.runPipeline).toHaveBeenCalledWith({ source: 'CSV', folder: 'sample' });
  });

  it('hides the run form from audit managers', () => {
    const { fixture, api } = setup(['AUDIT_MANAGER']);
    expect(fixture.componentInstance.canRun).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('Start a run');
    expect(api.pipelineSources).not.toHaveBeenCalled();
  });

  it('polls while a run is active', fakeAsync(() => {
    const { api } = setup(['AUDITOR'], [{ ...RUN, status: 'RUNNING' }]);
    expect(api.pipelineRuns).toHaveBeenCalledTimes(1);
    tick(2000);
    expect(api.pipelineRuns).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('does not poll when nothing is running', fakeAsync(() => {
    const { api } = setup(['AUDITOR']);
    tick(6000);
    expect(api.pipelineRuns).toHaveBeenCalledTimes(1);
  }));
});
