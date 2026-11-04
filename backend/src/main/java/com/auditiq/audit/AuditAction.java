package com.auditiq.audit;

/** Catalogue of audited actions. Stored as strings so new actions never need a migration. */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    TOKEN_REFRESH,
    TOKEN_REUSE_DETECTED,
    USER_REGISTERED,
    USER_CREATED,
    USER_ENABLED,
    USER_DISABLED,
    USER_UNLOCKED,
    USER_ROLES_CHANGED,
    AUDIT_LOG_VIEWED
}
