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
 * Persistence model for the payment_webhook_events table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class PaymentWebhookEvent {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @JsonProperty("event_id")
    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @JsonProperty("payment_order_id")
    @Column(name = "payment_order_id", nullable = false)
    private UUID paymentOrderId;

    @JsonProperty("received_at")
    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime receivedAt;

}
