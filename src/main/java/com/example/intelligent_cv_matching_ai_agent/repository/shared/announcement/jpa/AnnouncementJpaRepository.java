package com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.announcement.Announcement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for announcements. */
public interface AnnouncementJpaRepository extends JpaRepository<Announcement, UUID> {
}
