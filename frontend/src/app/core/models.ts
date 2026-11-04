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

export interface PipelineRunSummary {
  id: number;
  runRef: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  triggerType: string;
  triggeredBy?: string;
  source?: string;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  totalRecords?: number;
  validRecords?: number;
  rejectedRecords?: number;
  duplicateRecords?: number;
}

export interface PipelineStep {
  name: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  startedAt: string;
  durationMs?: number;
  rows?: number;
  message?: string;
  details?: Record<string, unknown>;
}

export interface QualityReport {
  pipelineRunId: number;
  runRef: string;
  totalRecords: number;
  validRecords: number;
  rejectedRecords: number;
  duplicateRecords: number;
  missingValueTotal: number;
  missingValues: Record<string, number>;
  rejectionReasons: Record<string, number>;
  processingDurationMs: number;
  status: string;
  createdAt: string;
  validRatePercent: number;
}

export interface PipelineRunDetail {
  run: PipelineRunSummary;
  parameters: Record<string, unknown>;
  steps: PipelineStep[];
  errorMessage?: string;
  qualityReport?: QualityReport;
}

export interface RunPipelineRequest {
  source: 'GENERATED' | 'CSV';
  seed?: number | null;
  transactionCount?: number | null;
  folder?: string | null;
}

export interface TransactionRow {
  transactionId: string;
  accountId: string;
  customerId: string;
  transactionDate: string;
  amount: number;
  currency: string;
  transactionType: string;
  channel: string;
  merchantId?: string;
  merchantName?: string;
  merchantCategory?: string;
  location?: string;
  accountType: string;
  status: string;
  approvalThreshold?: number;
  approvalStatus: string;
}

export interface TransactionDetail {
  transaction: TransactionRow;
  account: {
    accountId: string;
    customerId: string;
    accountType: string;
    status: string;
    branch?: string;
    openedDate: string;
    lastActivityBeforeWindow?: string;
    transactionCount: number;
    averageAmount?: number;
    firstTransactionAt?: string;
    lastTransactionAt?: string;
  };
  merchant?: { merchantId: string; merchantName: string; category: string; city?: string; vendor: boolean };
  lineage: { pipelineRunId?: number; runRef?: string; loadedAt: string; originalAmount?: number; originalCurrency?: string };
  exceptions: {
    id: number;
    exceptionRef: string;
    detectionSource: string;
    ruleId?: string;
    severity: string;
    riskCategory: string;
    status: string;
    detectedAt: string;
  }[];
}

export interface Bucket {
  key: string;
  count: number;
  value: number;
}

export interface TransactionAnalytics {
  totals: {
    count: number;
    value: number;
    averageValue: number;
    accounts: number;
    merchants: number;
    firstTransactionAt?: string;
    lastTransactionAt?: string;
  };
  monthly: Bucket[];
  byCategory: Bucket[];
  byChannel: Bucket[];
  byType: Bucket[];
  byStatus: Bucket[];
  byLocation: Bucket[];
}

export interface TransactionQuery {
  q?: string;
  accountId?: string;
  type?: string;
  channel?: string;
  category?: string;
  status?: string;
  from?: string;
  to?: string;
  minAmount?: number | null;
  maxAmount?: number | null;
  sort?: 'transactionDate' | 'amount' | 'accountId';
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}
