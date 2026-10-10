package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.identity.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for users. */
public interface UserJpaRepository extends JpaRepository<User, UUID> {
}
