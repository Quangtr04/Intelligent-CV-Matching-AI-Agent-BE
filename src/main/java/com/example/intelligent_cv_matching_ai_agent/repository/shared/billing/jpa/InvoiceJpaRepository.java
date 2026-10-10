package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.billing.Invoice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for invoices. */
public interface InvoiceJpaRepository extends JpaRepository<Invoice, UUID> {
}
