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
 * Persistence model for the payment_reports table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "payment_reports")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class PaymentReport {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("payment_order_id")
    @Column(name = "payment_order_id", nullable = false)
    private UUID paymentOrderId;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("assigned_admin_id")
    @Column(name = "assigned_admin_id", nullable = true)
    private UUID assignedAdminId;

    @JsonProperty("subject")
    @Column(name = "subject", nullable = true, length = 255)
    private String subject;

    @JsonProperty("description")
    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JsonProperty("resolution")
    @Column(name = "resolution", nullable = true, columnDefinition = "text")
    private String resolution;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("resolved_at")
    @Column(name = "resolved_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime resolvedAt;

}
