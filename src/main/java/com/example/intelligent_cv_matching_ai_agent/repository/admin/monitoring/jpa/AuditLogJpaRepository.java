package com.example.intelligent_cv_matching_ai_agent.repository.admin.monitoring.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for audit_logs. */
public interface AuditLogJpaRepository extends JpaRepository<AuditLog, UUID> {
}
