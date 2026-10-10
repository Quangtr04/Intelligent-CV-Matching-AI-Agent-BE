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
 * Persistence model for the subscription_tiers table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "subscription_tiers")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SubscriptionTier {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("tier_code")
    @Column(name = "tier_code", nullable = false, length = 30)
    private String tierCode;

    @JsonProperty("display_name")
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @JsonProperty("price_monthly")
    @Column(name = "price_monthly", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceMonthly;

    @JsonProperty("currency")
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
