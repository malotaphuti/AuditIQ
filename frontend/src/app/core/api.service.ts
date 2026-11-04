import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AuditLogEntry,
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
}
