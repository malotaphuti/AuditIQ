import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AuditLogEntry,
  RiskDashboard,
  DetectResult,
  MlSettings,
  ModelPerformance,
  ModelVersion,
  TrainResult,
  AuditRule,
  CreateInvestigationRequest,
  ExceptionDetail,
  ExceptionQuery,
  ExceptionRow,
  ExceptionSummary,
  ExecuteRulesResponse,
  InvestigationDetail,
  InvestigationQuery,
  InvestigationRow,
  InvestigationSummary,
  RuleExecution,
  StatusChangeRequest,
  UpdateRuleRequest,
  UserRef,
  PipelineRunDetail,
  PipelineRunSummary,
  QualityReport,
  RunPipelineRequest,
  TransactionAnalytics,
  TransactionDetail,
  TransactionQuery,
  TransactionRow,
  CreateUserRequest,
  Overview,
  PageResponse,
  RoleName,
  SystemStatus,
  UserAccount,
} from './models';

export interface AuditLogFilter {
  action?: string;
  username?: string;
  entityType?: string;
  page?: number;
  size?: number;
}

function params(obj: object): HttpParams {
  let p = new HttpParams();
  Object.entries(obj).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') {
      p = p.set(k, String(v));
    }
  });
  return p;
}

/** Thin typed wrapper over the REST API. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  overview(): Observable<Overview> {
    return this.http.get<Overview>('/api/overview');
  }

  systemStatus(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>('/api/system/status');
  }

  users(search: string, page: number, size: number): Observable<PageResponse<UserAccount>> {
    return this.http.get<PageResponse<UserAccount>>('/api/admin/users', {
      params: params({ search, page, size }),
    });
  }

  createUser(request: CreateUserRequest): Observable<UserAccount> {
    return this.http.post<UserAccount>('/api/admin/users', request);
  }

  setUserEnabled(id: number, enabled: boolean): Observable<UserAccount> {
    return this.http.patch<UserAccount>(`/api/admin/users/${id}/status`, { enabled });
  }

  setUserRoles(id: number, roles: RoleName[]): Observable<UserAccount> {
    return this.http.put<UserAccount>(`/api/admin/users/${id}/roles`, { roles });
  }

  unlockUser(id: number): Observable<UserAccount> {
    return this.http.post<UserAccount>(`/api/admin/users/${id}/unlock`, {});
  }

  auditLogs(filter: AuditLogFilter): Observable<PageResponse<AuditLogEntry>> {
    return this.http.get<PageResponse<AuditLogEntry>>('/api/admin/audit-logs', {
      params: params(filter),
    });
  }

  runPipeline(request: RunPipelineRequest): Observable<{ runId: number; runRef: string; status: string }> {
    return this.http.post<{ runId: number; runRef: string; status: string }>('/api/pipeline/run', request);
  }

  pipelineRuns(page = 0, size = 20): Observable<PageResponse<PipelineRunSummary>> {
    return this.http.get<PageResponse<PipelineRunSummary>>('/api/pipeline/runs', { params: params({ page, size }) });
  }

  pipelineRun(id: number): Observable<PipelineRunDetail> {
    return this.http.get<PipelineRunDetail>(`/api/pipeline/runs/${id}`);
  }

  qualityReport(runId?: number): Observable<QualityReport> {
    return this.http.get<QualityReport>('/api/pipeline/quality-report', { params: params({ runId }) });
  }

  pipelineSources(): Observable<{ folders: string[] }> {
    return this.http.get<{ folders: string[] }>('/api/pipeline/sources');
  }

  transactions(query: TransactionQuery): Observable<PageResponse<TransactionRow>> {
    return this.http.get<PageResponse<TransactionRow>>('/api/transactions', { params: params(query) });
  }

  transaction(id: string): Observable<TransactionDetail> {
    return this.http.get<TransactionDetail>(`/api/transactions/${encodeURIComponent(id)}`);
  }

  transactionAnalytics(from?: string, to?: string): Observable<TransactionAnalytics> {
    return this.http.get<TransactionAnalytics>('/api/transactions/analytics', { params: params({ from, to }) });
  }

  // -- audit rules --------------------------------------------------------------------------------------------------
  auditRules(): Observable<AuditRule[]> {
    return this.http.get<AuditRule[]>('/api/audit-rules');
  }

  updateAuditRule(ruleId: string, request: UpdateRuleRequest): Observable<AuditRule> {
    return this.http.put<AuditRule>(`/api/audit-rules/${encodeURIComponent(ruleId)}`, request);
  }

  executeAuditRules(ruleIds?: string[]): Observable<ExecuteRulesResponse> {
    return this.http.post<ExecuteRulesResponse>('/api/audit-rules/execute', ruleIds ? { ruleIds } : {});
  }

  ruleExecutions(ruleId?: string, page = 0, size = 20): Observable<PageResponse<RuleExecution>> {
    return this.http.get<PageResponse<RuleExecution>>('/api/audit-rules/executions', {
      params: params({ ruleId, page, size }),
    });
  }

  // -- exceptions ---------------------------------------------------------------------------------------------------
  exceptions(query: ExceptionQuery): Observable<PageResponse<ExceptionRow>> {
    return this.http.get<PageResponse<ExceptionRow>>('/api/exceptions', { params: params(query) });
  }

  exceptionSummary(): Observable<ExceptionSummary> {
    return this.http.get<ExceptionSummary>('/api/exceptions/summary');
  }

  exception(id: number): Observable<ExceptionDetail> {
    return this.http.get<ExceptionDetail>(`/api/exceptions/${id}`);
  }

  // -- investigations -----------------------------------------------------------------------------------------------
  investigations(query: InvestigationQuery): Observable<PageResponse<InvestigationRow>> {
    return this.http.get<PageResponse<InvestigationRow>>('/api/investigations', { params: params(query) });
  }

  investigationSummary(): Observable<InvestigationSummary> {
    return this.http.get<InvestigationSummary>('/api/investigations/summary');
  }

  investigation(id: number): Observable<InvestigationDetail> {
    return this.http.get<InvestigationDetail>(`/api/investigations/${id}`);
  }

  assignees(): Observable<UserRef[]> {
    return this.http.get<UserRef[]>('/api/investigations/assignees');
  }

  createInvestigation(request: CreateInvestigationRequest): Observable<InvestigationDetail> {
    return this.http.post<InvestigationDetail>('/api/investigations', request);
  }

  changeInvestigationStatus(id: number, request: StatusChangeRequest): Observable<InvestigationDetail> {
    return this.http.put<InvestigationDetail>(`/api/investigations/${id}/status`, request);
  }

  assignInvestigation(id: number, assigneeId: number | null, version: number): Observable<InvestigationDetail> {
    return this.http.put<InvestigationDetail>(`/api/investigations/${id}/assignment`, { assigneeId, version });
  }

  addInvestigationNote(id: number, note: string): Observable<InvestigationDetail> {
    return this.http.post<InvestigationDetail>(`/api/investigations/${id}/notes`, { note });
  }

  // -- anomaly model ------------------------------------------------------------------------------------------------
  trainModel(params: { nEstimators?: number; maxSamples?: number; seed?: number } = {}): Observable<TrainResult> {
    return this.http.post<TrainResult>('/api/ml/train', params);
  }

  detectAnomalies(): Observable<DetectResult> {
    return this.http.post<DetectResult>('/api/ml/detect', {});
  }

  modelPerformance(versionId?: number): Observable<ModelPerformance> {
    return this.http.get<ModelPerformance>('/api/ml/model-performance', { params: params({ versionId }) });
  }

  modelVersions(): Observable<ModelVersion[]> {
    return this.http.get<ModelVersion[]>('/api/ml/models');
  }

  mlSettings(): Observable<MlSettings> {
    return this.http.get<MlSettings>('/api/ml/settings');
  }

  updateMlSettings(settings: Omit<MlSettings, 'updatedBy' | 'updatedAt'>): Observable<MlSettings> {
    return this.http.put<MlSettings>('/api/ml/settings', settings);
  }

  // -- risk analytics -----------------------------------------------------------------------------------------------
  riskDashboard(from?: string, to?: string): Observable<RiskDashboard> {
    return this.http.get<RiskDashboard>('/api/analytics/dashboard', { params: params({ from, to }) });
  }

  analyticsDatasets(): Observable<string[]> {
    return this.http.get<string[]>('/api/analytics/datasets');
  }

  exportDataset(dataset: string): Observable<Blob> {
    return this.http.get(`/api/analytics/export/${encodeURIComponent(dataset)}`, { responseType: 'blob' });
  }
}
