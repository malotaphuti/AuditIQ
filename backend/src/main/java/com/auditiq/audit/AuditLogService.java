package com.auditiq.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.auditiq.audit.AuditLog.Outcome;
import com.auditiq.common.PageResponse;
import com.auditiq.common.RequestContext;
import com.auditiq.security.AuthenticatedUser;
import com.auditiq.security.CurrentUser;

import jakarta.persistence.criteria.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail. Entries are written in their own transaction so that a failed business operation is still
 * recorded (e.g. a rejected login), and an audit-write failure never silently passes: it is logged as an error.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditLogService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Records an action performed by the currently authenticated user. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, Object entityId, Map<String, Object> details) {
        AuthenticatedUser user = CurrentUser.get().orElse(null);
        write(user == null ? null : user.id(), user == null ? null : user.username(), action.name(), entityType,
                entityId, Outcome.SUCCESS, details);
    }

    /** Records an action by the current user with an explicit outcome (e.g. a rejected request). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, Object entityId, Outcome outcome,
                       Map<String, Object> details) {
        AuthenticatedUser user = CurrentUser.get().orElse(null);
        write(user == null ? null : user.id(), user == null ? null : user.username(), action.name(), entityType,
                entityId, outcome, details);
    }

    /** Records an action for an explicitly identified actor (used before authentication completes). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFor(Long userId, String username, AuditAction action, Outcome outcome,
                          Map<String, Object> details) {
        write(userId, username, action.name(), "USER", userId, outcome, details);
    }

    private void write(Long userId, String username, String action, String entityType, Object entityId,
                       Outcome outcome, Map<String, Object> details) {
        try {
            String safeUsername = username != null && username.length() > 50 ? username.substring(0, 50) : username;
            repository.save(new AuditLog(Instant.now(clock), userId, safeUsername, action, entityType,
                    entityId == null ? null : entityId.toString(), outcome, details,
                    RequestContext.clientIp(), RequestContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("AUDIT WRITE FAILED action={} user={} entity={}:{}", action, username, entityType, entityId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditLogQuery query, int page, int size) {
        Specification<AuditLog> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.action() != null && !query.action().isBlank()) {
                predicates.add(cb.equal(root.get("action"), query.action().trim().toUpperCase()));
            }
            if (query.username() != null && !query.username().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("username")), query.username().trim().toLowerCase()));
            }
            if (query.entityType() != null && !query.entityType().isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), query.entityType().trim().toUpperCase()));
            }
            if (query.entityId() != null && !query.entityId().isBlank()) {
                predicates.add(cb.equal(root.get("entityId"), query.entityId().trim()));
            }
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), query.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        return PageResponse.from(repository.findAll(spec, pageable), AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> recent(AuthenticatedUser viewer, boolean viewAll) {
        List<AuditLog> rows = viewAll ? repository.findTop10ByOrderByOccurredAtDesc()
                : repository.findTop10ByUserIdOrderByOccurredAtDesc(viewer.id());
        return rows.stream().map(AuditLogResponse::from).toList();
    }

    public record AuditLogQuery(String action, String username, String entityType, String entityId,
                                Instant from, Instant to) {
    }
}
