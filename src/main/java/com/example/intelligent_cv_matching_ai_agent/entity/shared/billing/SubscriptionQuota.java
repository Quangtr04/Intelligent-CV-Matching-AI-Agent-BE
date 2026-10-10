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
 * Persistence model for the subscription_quotas table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "subscription_quotas")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SubscriptionQuota {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("tier_id")
    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    @JsonProperty("feature_code")
    @Column(name = "feature_code", nullable = false, length = 100)
    private String featureCode;

    @JsonProperty("quota_value")
    @Column(name = "quota_value", nullable = false)
    private Long quotaValue;

    @JsonProperty("quota_period")
    @Column(name = "quota_period", nullable = true, length = 30)
    private String quotaPeriod;

}
