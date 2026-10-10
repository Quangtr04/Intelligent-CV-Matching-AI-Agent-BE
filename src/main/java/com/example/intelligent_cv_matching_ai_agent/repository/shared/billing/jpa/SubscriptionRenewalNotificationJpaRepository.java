package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.billing.SubscriptionRenewalNotification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for subscription_renewal_notifications. */
public interface SubscriptionRenewalNotificationJpaRepository
    extends
    JpaRepository<SubscriptionRenewalNotification, UUID> {
}
