package com.example.intelligent_cv_matching_ai_agent.entity.shared.announcement;

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
 * Persistence model for the announcement_targets table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "announcement_targets")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AnnouncementTarget {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("announcement_id")
    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @JsonProperty("target_type")
    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @JsonProperty("target_role")
    @Column(name = "target_role", nullable = true, length = 32)
    private String targetRole;

    @JsonProperty("target_user_id")
    @Column(name = "target_user_id", nullable = true)
    private UUID targetUserId;

}
