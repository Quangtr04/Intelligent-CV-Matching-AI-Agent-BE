package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.billing.PaymentReport;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for payment_reports. */
public interface PaymentReportJpaRepository extends JpaRepository<PaymentReport, UUID> {
}
