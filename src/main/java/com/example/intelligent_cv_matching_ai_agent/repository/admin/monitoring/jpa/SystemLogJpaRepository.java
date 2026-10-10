package com.example.intelligent_cv_matching_ai_agent.repository.admin.monitoring.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring.SystemLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for system_logs. */
public interface SystemLogJpaRepository extends JpaRepository<SystemLog, UUID> {
}
