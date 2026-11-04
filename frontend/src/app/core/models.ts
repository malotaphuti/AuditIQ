/** Types mirroring the Spring Boot API contracts. */

export type RoleName = 'ADMIN' | 'AUDITOR' | 'AUDIT_MANAGER';

export interface UserSummary {
  id: number;
  username: string;
  fullName: string;
  email: string;
  roles: RoleName[];
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: Record<string, string>;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AuditLogEntry {
  id: number;
  occurredAt: string;
  userId?: number;
  username?: string;
  action: string;
  entityType?: string;
  entityId?: string;
  outcome: 'SUCCESS' | 'FAILURE';
  details: Record<string, unknown>;
  ipAddress?: string;
}

export interface UserAccount {
  id: number;
  username: string;
  email: string;
  fullName: string;
  enabled: boolean;
  locked: boolean;
  roles: RoleName[];
  lastLoginAt?: string;
  createdAt: string;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  fullName: string;
  password: string;
  roles: RoleName[];
}

export interface Overview {
  generatedAt: string;
  transactions: {
    total: number;
    totalValue: number;
    firstTransactionAt?: string;
    lastTransactionAt?: string;
  };
  exceptions: {
    total: number;
    highPriority: number;
    open: number;
    exceptionRatePercent: number;
    bySeverity: Record<string, number>;
    bySource: Record<string, number>;
  };
  investigationsByStatus: Record<string, number>;
  pipeline: {
    totalRuns: number;
    successfulRuns: number;
    lastRun?: {
      runRef: string;
      status: string;
      startedAt: string;
      durationMs?: number;
      totalRecords?: number;
      validRecords?: number;
      rejectedRecords?: number;
    };
  };
  transactionTrend: { date: string; count: number; value: number }[];
  riskDistribution: { category: string; count: number }[];
  recentExceptions: {
    id: number;
    exceptionRef: string;
    detectionSource: string;
    ruleId?: string;
    transactionId: string;
    accountId: string;
    severity: string;
    riskCategory: string;
    status: string;
    detectedAt: string;
  }[];
  recentActivity: AuditLogEntry[];
}

export interface SystemStatus {
  checkedAt: string;
  database: 'UP' | 'DOWN';
  analyticsService: 'UP' | 'DOWN' | 'DEGRADED';
  version: string;
}
