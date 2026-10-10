package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.billing.SubscriptionQuota;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for subscription_quotas. */
public interface SubscriptionQuotaJpaRepository extends JpaRepository<SubscriptionQuota, UUID> {
}
