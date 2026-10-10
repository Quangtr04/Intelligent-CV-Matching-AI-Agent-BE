package com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring;

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
 * Persistence model for the admin_actions table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "admin_actions")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AdminAction {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("admin_user_id")
    @Column(name = "admin_user_id", nullable = false)
    private UUID adminUserId;

    @JsonProperty("action_type")
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @JsonProperty("target_type")
    @Column(name = "target_type", nullable = true, length = 50)
    private String targetType;

    @JsonProperty("target_id")
    @Column(name = "target_id", nullable = true)
    private UUID targetId;

    @JsonProperty("metadata")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = true, columnDefinition = "jsonb")
    private Object metadata;

    @JsonProperty("ip_address")
    @ColumnTransformer(read = "ip_address::text", write = "?::inet")
    @Column(name = "ip_address", nullable = true, columnDefinition = "inet")
    private String ipAddress;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
