package com.example.intelligent_cv_matching_ai_agent.config.shared.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Validated
@ConfigurationProperties(prefix = "app.jobs.collection")
public class JobCollectionProperties {

    private boolean enabled;

    private UUID actorId;

    @NotNull
    private Duration fixedDelay = Duration.ofHours(6);

    @Valid
    @NotNull
    @Size(max = 20)
    private List<Source> sources = new ArrayList<>();

    @AssertTrue(message = "Enabled collection requires an actor-id and at least one source")
    public boolean isActorConfigured() {
        return !enabled || actorId != null && sources != null && !sources.isEmpty();
    }

    @AssertTrue(message = "Collection fixed-delay must be positive")
    public boolean isDelayValid() {
        return fixedDelay != null && !fixedDelay.isNegative() && !fixedDelay.isZero();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(
        boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(
        UUID actorId) {
        this.actorId = actorId;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(
        Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(
        List<Source> sources) {
        this.sources = sources;
    }

    public static class Source {

        @NotBlank
        @Pattern(regexp = "ITVIEC|TOPCV")
        private String sourceSite;

        @NotBlank
        @Size(max = 255)
        private String keyword;

        @NotBlank
        @Size(max = 100)
        private String roleCategory;

        @Size(max = 120)
        private String location;

        @Min(1)
        @Max(100)
        private int limit = 50;

        @Min(1)
        @Max(10)
        private int maxPages = 5;

        public String getSourceSite() {
            return sourceSite;
        }

        public void setSourceSite(
            String sourceSite) {
            this.sourceSite = sourceSite;
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(
            String keyword) {
            this.keyword = keyword;
        }

        public String getRoleCategory() {
            return roleCategory;
        }

        public void setRoleCategory(
            String roleCategory) {
            this.roleCategory = roleCategory;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(
            String location) {
            this.location = location;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(
            int limit) {
            this.limit = limit;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(
            int maxPages) {
            this.maxPages = maxPages;
        }
    }
}
