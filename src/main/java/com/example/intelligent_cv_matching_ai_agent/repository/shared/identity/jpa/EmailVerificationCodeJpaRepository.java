package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.identity.EmailVerificationCode;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for email_verification_codes. */
public interface EmailVerificationCodeJpaRepository extends JpaRepository<EmailVerificationCode, UUID> {
}
