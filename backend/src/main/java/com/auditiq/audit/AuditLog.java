package com.auditiq.audit;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable audit-trail entry. */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    public enum Outcome { SUCCESS, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(updatable = false, length = 50)
    private String username;

    @Column(nullable = false, updatable = false, length = 60)
    private String action;

    @Column(name = "entity_type", updatable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", updatable = false, length = 80)
    private String entityId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    protected AuditLog() {
    }

    public AuditLog(Instant occurredAt, Long userId, String username, String action, String entityType,
                    String entityId, Outcome outcome, Map<String, Object> details, String ipAddress,
                    String userAgent) {
        this.occurredAt = occurredAt;
        this.userId = userId;
        this.username = username;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.details = details == null ? new HashMap<>() : new HashMap<>(details);
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
