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
 * Persistence model for the subscription_renewal_notifications table; foreign
 * keys retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "subscription_renewal_notifications")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SubscriptionRenewalNotification {

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
    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @JsonProperty("notification_type")
    @Column(name = "notification_type", nullable = false, length = 20)
    private String notificationType;

    @JsonProperty("sent_at")
    @Column(name = "sent_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime sentAt;

}
