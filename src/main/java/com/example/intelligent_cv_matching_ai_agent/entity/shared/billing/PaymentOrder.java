package com.example.intelligent_cv_matching_ai_agent.entity.shared.billing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Persistence model for the payment_orders table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "payment_orders")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class PaymentOrder {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("subscription_id")
    @Column(name = "subscription_id", nullable = true)
    private UUID subscriptionId;

    @JsonProperty("order_type")
    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType;

    @JsonProperty("consultation_order_id")
    @Column(name = "consultation_order_id", nullable = true)
    private UUID consultationOrderId;

    @JsonProperty("tier_id")
    @Column(name = "tier_id", nullable = true)
    private UUID tierId;

    @JsonProperty("duration_months")
    @Column(name = "duration_months", nullable = true)
    private Short durationMonths;

    @JsonProperty("amount")
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @JsonProperty("currency")
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @JsonProperty("payment_provider")
    @Column(name = "payment_provider", nullable = false, length = 20)
    private String paymentProvider;

    @JsonProperty("provider_order_id")
    @Column(name = "provider_order_id", nullable = true, length = 255)
    private String providerOrderId;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("completed_at")
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime completedAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
