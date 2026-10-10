package com.example.intelligent_cv_matching_ai_agent.entity.shared.identity;

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
 * Persistence model for the users table; foreign keys retain their explicit
 * UUID identifiers.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class User {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("email")
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @JsonProperty("password_hash")
    @JsonIgnore
    @Column(name = "password_hash", nullable = true, length = 255)
    private String passwordHash;

    @JsonProperty("auth_provider")
    @Column(name = "auth_provider", nullable = false, length = 32)
    private String authProvider;

    @JsonProperty("google_sub")
    @Column(name = "google_sub", nullable = true, length = 255)
    private String googleSub;

    @JsonProperty("full_name")
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @JsonProperty("avatar_url")
    @Column(name = "avatar_url", nullable = true, columnDefinition = "text")
    private String avatarUrl;

    @JsonProperty("role")
    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("preferred_language")
    @Column(name = "preferred_language", nullable = true, length = 5)
    private String preferredLanguage;

    @JsonProperty("github_url")
    @Column(name = "github_url", nullable = true, length = 255)
    private String githubUrl;

    @JsonProperty("email_verified_at")
    @Column(name = "email_verified_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime emailVerifiedAt;

    @JsonProperty("last_login_at")
    @Column(name = "last_login_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime lastLoginAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

    @JsonProperty("deleted_at")
    @Column(name = "deleted_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime deletedAt;

}
