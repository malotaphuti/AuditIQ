package com.auditiq.user;

/** Application roles. Spring authorities are derived as {@code ROLE_<name>}. */
public enum RoleName {
    ADMIN,
    AUDITOR,
    AUDIT_MANAGER;

    public String authority() {
        return "ROLE_" + name();
    }
}
