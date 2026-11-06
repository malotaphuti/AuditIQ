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
  recentExceptions: RecentException[];
  recentActivity: AuditLogEntry[];
}

export interface RecentException {
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

export interface AnomalyScore {
  score: number;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  flagged: boolean;
  modelVersion: string;
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
  /** Score from the active anomaly model, if one has been trained. */
  anomaly?: AnomalyScore | null;
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

// ---------------------------------------------------------------------------------------------------------------------
// Phase 3 – audit rules, exceptions and investigations
// ---------------------------------------------------------------------------------------------------------------------
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ExceptionStatus = 'OPEN' | 'UNDER_INVESTIGATION' | 'CLOSED';
export type InvestigationStatus = 'NEW' | 'UNDER_REVIEW' | 'AWAITING_EVIDENCE' | 'ESCALATED' | 'RESOLVED';
export type InvestigationOutcome =
  | 'NO_EXCEPTION'
  | 'CONTROL_WEAKNESS'
  | 'POLICY_BREACH'
  | 'DATA_QUALITY_ISSUE'
  | 'REFERRED_FOR_FURTHER_REVIEW';

export interface ThresholdSpec {
  type: 'number' | 'integer' | 'boolean' | 'list';
  label?: string;
  min?: number;
  max?: number;
  allowed?: string[];
}

export type ThresholdValue = number | boolean | string[];

export interface RuleExecution {
  id: number;
  ruleId: string;
  ruleName: string;
  batchRef: string | null;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  ruleVersion: number | null;
  matchesTotal: number | null;
  exceptionsCreated: number;
  executedBy: string | null;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
  thresholdsUsed: Record<string, ThresholdValue>;
}

export interface AuditRule {
  ruleId: string;
  ruleName: string;
  description: string;
  detectionLogic: string;
  riskCategory: string;
  severity: Severity;
  thresholds: Record<string, ThresholdValue>;
  thresholdSchema: Record<string, ThresholdSpec>;
  enabled: boolean;
  version: number;
  lastExecutedAt: string | null;
  updatedAt: string;
  updatedBy: string | null;
  exceptionsTotal: number;
  exceptionsOpen: number;
  lastExecution: RuleExecution | null;
}

export interface UpdateRuleRequest {
  thresholds: Record<string, ThresholdValue>;
  enabled: boolean;
  severity: Severity;
}

export interface RuleOutcome {
  ruleId: string;
  ruleName: string;
  executionId: number;
  status: 'SUCCESS' | 'FAILED';
  matchesTotal: number;
  exceptionsCreated: number;
  durationMs: number;
  error: string | null;
}

export interface ExecuteRulesResponse {
  batchRef: string;
  durationMs: number;
  exceptionsCreated: number;
  matchesTotal: number;
  results: RuleOutcome[];
}

export interface InvestigationLink {
  id: number;
  investigationRef: string;
  status: InvestigationStatus;
  assignedTo: string | null;
}

export interface ExceptionRow {
  id: number;
  exceptionRef: string;
  detectionSource: 'RULE' | 'ML';
  ruleId: string | null;
  ruleName: string | null;
  transactionId: string;
  accountId: string;
  transactionDate: string;
  amount: number;
  transactionType: string;
  severity: Severity;
  riskCategory: string;
  status: ExceptionStatus;
  reason: string;
  detectedAt: string;
  /** Active anomaly model's score for the transaction (unusualness, not fraud probability). */
  anomalyScore: number | null;
  anomalyPriority: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  investigation: InvestigationLink | null;
}

export interface ExceptionQuery {
  q?: string;
  severity?: string;
  riskCategory?: string;
  ruleId?: string;
  source?: string;
  status?: string;
  accountId?: string;
  from?: string;
  to?: string;
  sort?: string;
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface RelatedTransaction {
  transaction: TransactionRow;
  relation: string;
  exceptionRefs: string[];
}

export interface RuleInfo {
  ruleId: string;
  ruleName: string;
  description: string;
  detectionLogic: string;
  riskCategory: string;
  currentVersion: number;
}

export interface ExceptionDetail {
  exception: ExceptionRow;
  details: Record<string, unknown>;
  rule: RuleInfo | null;
  execution: { id: number; batchRef: string | null; executedBy: string | null; startedAt: string } | null;
  transaction: TransactionRow;
  linkedTransactions: RelatedTransaction[];
  accountActivity: RelatedTransaction[];
  otherFindings: ExceptionRow[];
  investigations: InvestigationLink[];
}

export interface CountItem {
  key: string;
  label: string;
  count: number;
}

export interface ExceptionSummary {
  total: number;
  open: number;
  underInvestigation: number;
  closed: number;
  bySeverity: Record<Severity, number>;
  byRiskCategory: CountItem[];
  byRule: CountItem[];
  bySource: Record<string, number>;
}

export interface UserRef {
  id: number;
  username: string;
  fullName: string;
}

export interface InvestigationRow {
  id: number;
  investigationRef: string;
  title: string;
  status: InvestigationStatus;
  priority: Severity;
  exceptionId: number;
  exceptionRef: string;
  ruleId: string | null;
  ruleName: string | null;
  transactionId: string;
  accountId: string;
  amount: number;
  assignedTo: UserRef | null;
  createdBy: UserRef;
  outcome: InvestigationOutcome | null;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
}

export interface InvestigationNote {
  id: number;
  author: UserRef;
  note: string;
  createdAt: string;
}

export interface StatusChange {
  id: number;
  fromStatus: InvestigationStatus | null;
  toStatus: InvestigationStatus;
  changedBy: UserRef;
  reason: string | null;
  changedAt: string;
}

export interface Evidence {
  id: number;
  evidenceType: string;
  referenceId: string | null;
  description: string;
  content: Record<string, unknown>;
  aiGenerated: boolean;
  humanReviewStatus: string;
  addedBy: UserRef | null;
  createdAt: string;
}

export interface InvestigationDetail {
  investigation: InvestigationRow;
  version: number;
  outcomeSummary: string | null;
  reviewedBy: UserRef | null;
  reviewedAt: string | null;
  exception: ExceptionRow;
  notes: InvestigationNote[];
  history: StatusChange[];
  evidence: Evidence[];
  activity: AuditLogEntry[];
  allowedTransitions: InvestigationStatus[];
  outcomes: InvestigationOutcome[];
}

export interface InvestigationSummary {
  byStatus: Record<InvestigationStatus, number>;
  open: number;
  assignedToMe: number;
  unassigned: number;
  exceptionsAwaitingReview: number;
  averageResolutionHours: number | null;
}

export interface InvestigationQuery {
  status?: string;
  priority?: string;
  assignee?: string;
  q?: string;
  sort?: string;
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface CreateInvestigationRequest {
  exceptionId: number;
  title?: string;
  priority?: Severity;
  assigneeId?: number;
  note?: string;
}

export interface StatusChangeRequest {
  status: InvestigationStatus;
  reason?: string;
  outcome?: InvestigationOutcome;
  outcomeSummary?: string;
  version: number;
}

// ---------------------------------------------------------------------------------------------------------------------
// Phase 4 – anomaly model
// ---------------------------------------------------------------------------------------------------------------------
export interface MlThresholds {
  anomaly: number;
  medium: number;
  high: number;
}

export interface PrfMetrics {
  precision: number;
  recall: number;
  f1: number;
  truePositive: number;
  falsePositive: number;
  falseNegative: number;
  flagged?: number;
  rocAuc?: number;
  averagePrecision?: number;
}

export interface ModelPerformance {
  modelVersionId: number;
  version: string;
  trainedAt: string;
  trainedBy: string;
  trainingRows: number;
  hyperparameters: Record<string, string | number>;
  features: { name: string; label: string }[];
  thresholds: MlThresholds;
  exceptionMinPriority: 'LOW' | 'MEDIUM' | 'HIGH';
  active: boolean;
  algorithm: string;
  scoredTransactions: number;
  scoreNote: string;
  evaluatedAt: string;
  scoreDistribution: { from: number; to: number; labelled: number; unlabelled: number }[];
  groundTruthAvailable: boolean;
  flagged: number;
  labelledAnomalies?: number;
  confusionMatrix?: { truePositive: number; falsePositive: number; falseNegative: number; trueNegative: number };
  metrics?: PrfMetrics;
  priorityBands?: { priority: 'HIGH' | 'MEDIUM' | 'LOW'; from: number; flagged: number; labelledAnomalies: number;
    precision: number | null }[];
  recallByType?: { anomalyType: string; labelled: number; modelRecall: number; ruleRecall: number }[];
  comparison?: {
    rulesExecuted: boolean;
    model: PrfMetrics;
    rules?: PrfMetrics;
    combined?: PrfMetrics;
    overlap?: { bothDetected: number; rulesOnly: number; modelOnly: number; neither: number };
  };
  falsePositives?: {
    count: number;
    share: number;
    alsoFlaggedByRules: number;
    scoreMedian: number | null;
    byTransactionType: { key: string; count: number }[];
    byChannel: { key: string; count: number }[];
    byTopFactor: { key: string; count: number }[];
  };
}

export interface ModelVersion {
  id: number;
  version: string;
  algorithm: string;
  trainedAt: string;
  trainedBy: string;
  trainingRows: number;
  hyperparameters: Record<string, string | number>;
  features: { name: string; label: string }[];
  priorityThresholds: MlThresholds;
  metrics: { metrics?: PrfMetrics; flagged?: number; trainingDurationMs?: number };
  active: boolean;
  anomalies: number;
  exceptions: number;
}

export interface TrainResult {
  modelVersionId: number;
  version: string;
  trainingRows: number;
  durationMs: number;
}

export interface DetectResult {
  modelVersionId: number;
  version: string;
  newlyScored: number;
  anomalies: number;
  byPriority: Record<'HIGH' | 'MEDIUM' | 'LOW', number>;
  exceptionsCreated: number;
  exceptionMinPriority: 'LOW' | 'MEDIUM' | 'HIGH';
  durationMs: number;
}

export interface MlSettings {
  anomalyThreshold: number;
  mediumThreshold: number;
  highThreshold: number;
  exceptionMinPriority: 'LOW' | 'MEDIUM' | 'HIGH';
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface AnomalyFactor {
  feature: string;
  label: string;
  description: string;
  value: number;
  typical: number;
  deviation: number;
  combined?: boolean;
}

// ---------------------------------------------------------------------------------------------------------------------
// Phase 5 – risk analytics dashboard
// ---------------------------------------------------------------------------------------------------------------------
export interface DashBucket {
  key: string;
  count: number;
  value: number | null;
}

export interface RiskDashboard {
  generatedAt: string;
  from: string;
  to: string;
  dataFrom: string | null;
  dataTo: string | null;
  executive: {
    transactions: number;
    totalValue: number;
    potentialExceptions: number;
    highPriority: number;
    flaggedTransactions: number;
    exceptionRatePercent: number;
    awaitingReview: number;
    openInvestigations: number;
    pipeline: { runs: number; successfulRuns: number; lastStatus: string | null; lastRunAt: string | null;
      lastValidRatePercent: number | null };
  };
  transactions: {
    daily: { date: string; count: number; value: number; flagged: number }[];
    monthly: DashBucket[];
    byCategory: DashBucket[];
    byChannel: DashBucket[];
    byType: DashBucket[];
    byLocation: DashBucket[];
    byHour: DashBucket[];
  };
  risk: {
    bySeverity: Record<string, number>;
    byRiskCategory: DashBucket[];
    byDetector: { key: string; name: string; type: string; exceptions: number; highPriority: number }[];
    byStatus: Record<string, number>;
    monthlyTrend: { month: string; transactions: number; flaggedTransactions: number; flaggedRatePercent: number;
      ruleFindings: number; modelFindings: number }[];
    ruleVsModel: { rulesOnly: number; modelOnly: number; both: number };
    /** Present for auditors and audit managers only. */
    topAccounts?: {
      accountId: string; accountType: string; branch: string | null; exceptions: number; ruleFindings: number;
      modelFindings: number; highPriority: number; detectors: number; flaggedAmount: number;
      maxAnomalyScore: number | null; awaitingReview: number; openInvestigations: number;
    }[];
  };
  investigations: {
    byStatus: Record<string, number>;
    open: number;
    inProgress: number;
    resolved: number;
    averageResolutionHours: number | null;
    oldestOpenAgeHours: number | null;
    awaitingReview: number;
    byOutcome: DashBucket[];
    openByAssignee: DashBucket[];
  };
}
