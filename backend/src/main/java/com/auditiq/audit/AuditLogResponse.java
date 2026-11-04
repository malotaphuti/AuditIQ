package com.auditiq.audit;

import java.time.Instant;
import java.util.Map;

public record AuditLogResponse(Long id, Instant occurredAt, Long userId, String username, String action,
                               String entityType, String entityId, String outcome, Map<String, Object> details,
                               String ipAddress) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getOccurredAt(), log.getUserId(), log.getUsername(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getOutcome().name(), log.getDetails(),
                log.getIpAddress());
    }
}
