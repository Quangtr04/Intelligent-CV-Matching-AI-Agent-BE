package com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.announcement.AnnouncementTarget;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for announcement_targets. */
public interface AnnouncementTargetJpaRepository extends JpaRepository<AnnouncementTarget, UUID> {
}
