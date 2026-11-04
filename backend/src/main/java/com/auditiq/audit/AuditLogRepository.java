package com.auditiq.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findTop10ByOrderByOccurredAtDesc();

    List<AuditLog> findTop10ByUserIdOrderByOccurredAtDesc(Long userId);

    List<AuditLog> findByActionOrderByOccurredAtDesc(String action);
}
