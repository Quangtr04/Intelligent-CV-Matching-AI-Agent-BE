package com.example.intelligent_cv_matching_ai_agent.dto.response.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.identity.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public profile projection; credential and provider-subject fields are
 * excluded.
 */
public record PersonalProfileResponse(
    UUID id,
    String email,
    @JsonProperty("auth_provider")
    String authProvider,
    @JsonProperty("full_name")
    String fullName,
    @JsonProperty("avatar_url")
    String avatarUrl,
    String role,
    String status,
    @JsonProperty("preferred_language")
    String preferredLanguage,
    @JsonProperty("github_url")
    String githubUrl,
    @JsonProperty("email_verified_at")
    OffsetDateTime emailVerifiedAt,
    @JsonProperty("last_login_at")
    OffsetDateTime lastLoginAt,
    @JsonProperty("created_at")
    OffsetDateTime createdAt
) {

    public static PersonalProfileResponse from(
        User account) {
        return new PersonalProfileResponse(
            account.getId(),
            account.getEmail(),
            account.getAuthProvider(),
            account.getFullName(),
            account.getAvatarUrl(),
            account.getRole(),
            account.getStatus(),
            account.getPreferredLanguage(),
            account.getGithubUrl(),
            account.getEmailVerifiedAt(),
            account.getLastLoginAt(),
            account.getCreatedAt()
        );
    }
}
