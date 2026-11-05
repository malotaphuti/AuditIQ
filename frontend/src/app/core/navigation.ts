import { RoleName } from './models';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  roles?: RoleName[];
  /** Phase in which the module is delivered; absent means available now. */
  plannedPhase?: number;
  description: string;
}

const ALL: RoleName[] = ['ADMIN', 'AUDITOR', 'AUDIT_MANAGER'];

/** Single source of truth for navigation, route roles and module descriptions. */
export const NAV_ITEMS: NavItem[] = [
  { path: 'overview', label: 'Overview', icon: 'dashboard', roles: ALL,
    description: 'Headline audit KPIs, pipeline health and recent activity.' },
  { path: 'transactions', label: 'Transactions', icon: 'receipt_long', roles: ['AUDITOR', 'AUDIT_MANAGER'],
    description: 'Browse and filter curated synthetic transactions.' },
  { path: 'pipeline', label: 'Data Pipeline', icon: 'account_tree', roles: ALL,
    description: 'Trigger ingestion runs and review data-quality reports.' },
  { path: 'exceptions', label: 'Audit Exceptions', icon: 'rule', roles: ['AUDITOR', 'AUDIT_MANAGER'],
    description: 'Potential exceptions raised by SQL audit rules and the anomaly model.' },
  { path: 'risk-analytics', label: 'Risk Analytics', icon: 'insights', roles: ALL,
    plannedPhase: 5, description: 'Interactive risk dashboards built on the analytics layer.' },
  { path: 'investigations', label: 'Investigations', icon: 'manage_search', roles: ['AUDITOR', 'AUDIT_MANAGER'],
    description: 'Case management with notes, assignment and human-reviewed outcomes.' },
  { path: 'assistant', label: 'AI Audit Assistant', icon: 'forum', roles: ['AUDITOR', 'AUDIT_MANAGER'],
    plannedPhase: 6, description: 'Evidence-grounded answers with policy citations (RAG).' },
  { path: 'agent', label: 'AI Investigation Agent', icon: 'smart_toy', roles: ['AUDITOR'],
    plannedPhase: 7, description: 'Controlled agent that drafts investigation reports for human review.' },
  { path: 'reports', label: 'Reports', icon: 'summarize', roles: ['AUDITOR', 'AUDIT_MANAGER'],
    plannedPhase: 5, description: 'Model performance, rule comparison and investigation reports.' },
  { path: 'admin', label: 'Administration', icon: 'admin_panel_settings', roles: ['ADMIN', 'AUDIT_MANAGER'],
    description: 'User management and audit-rule configuration (Administrators) and the audit trail.' },
];
