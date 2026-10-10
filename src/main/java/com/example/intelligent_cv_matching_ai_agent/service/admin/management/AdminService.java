package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiConfigurationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiPromptRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.PaymentReportResolutionRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionQuotaRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionTierRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountCreateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import jakarta.validation.constraints.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {

    private final AdminRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    private final TokenService tokens;

    private final PasswordEncoder encoder;

    public AdminService(
        AdminRepository db,
        CurrentUser user,
        AuditService audit,
        TokenService tokens,
        PasswordEncoder encoder) {
        this.db = db;
        this.user = user;
        this.audit = audit;
        this.tokens = tokens;
        this.encoder = encoder;
    }

    private void admin() {
        user.requireRole("ADMIN");
    }

    public Map<String, Object> dashboard() {
        admin();
        return Map.of(
            "users",
            db.countUsersByStatus(),
            "activeSubscriptions",
            db.countUserSubscriptionsByStatus(),
            "revenue",
            db.listPaymentOrdersByStatus(),
            "aiUsage",
            db.getAiUsageLogsByCreatedAt(),
            "openSupportTickets",
            db.countSupportTicketsByStatus()
        );
    }

    public List<Map<String, Object>> users(
        @Size(max = 255)
        String search,
        @Min(0)
        int page,
        @Min(1)
        @Max(100)
        int size) {
        admin();

        return db.searchAccounts(search, size, (long) page * size);
    }

    public Map<String, Object> account(
        UUID id) {
        admin();

        return db.getAccount(id);
    }

    @Transactional
    public Map<String, Object> create(
        AccountCreateRequest body) {
        admin();
        String email = body.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (body.temporaryPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("Password must fit within 72 UTF-8 bytes");
        if (db.findUsersByEmail(email).isPresent())
            throw ApiException.conflict("Email already registered");

        OffsetDateTime now = OffsetDateTime.now();

        UUID id = db.insertUsers(
            Map.of(
                "email",
                email,
                "full_name",
                body.fullName(),
                "password_hash",
                encoder.encode(body.temporaryPassword()),
                "auth_provider",
                "EMAIL",
                "role",
                body.role(),
                "status",
                "ACTIVE",
                "email_verified_at",
                now,
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        if ("CANDIDATE".equals(body.role()))
            db.updateCandidateProfiles(id);

        Map<String, Object> account = account(id);

        audit.record("CREATE_USER", "USER", id, null, account);

        return account;
    }

    @Transactional
    public Map<String, Object> edit(
        UUID id,
        AccountUpdateRequest body) {
        admin();
        db.readDatabaseValue();
        Map<String, Object> before = account(id);
        if ("DELETED".equals(before.get("status")))
            throw ApiException.conflict("Deleted accounts cannot be reactivated");

        protectLastAdmin(before, body.role(), body.status());
        if ("ACTIVE".equals(body.status()) && before.get("email_verified_at") == null)
            throw ApiException.badRequest("Accounts must verify their email before activation");

        db.updateUsersById(body.fullName(), body.role(), body.status(), id);

        boolean roleChanged = !body.role().equals(before.get("role"));

        boolean accountInactive = !"ACTIVE".equals(body.status());

        if (roleChanged || accountInactive) {
            tokens.revokeAll(id);
        }

        Map<String, Object> after = account(id);

        audit.record("UPDATE_USER", "USER", id, before, after);

        return after;
    }

    @Transactional
    public Map<String, Object> delete(
        UUID id) {
        admin();
        db.readDatabaseValue();
        Map<String, Object> before = account(id);
        protectLastAdmin(before, before.get("role").toString(), "DELETED");

        db.updateUsersById2(id);
        tokens.revokeAll(id);

        audit.record("DELETE_USER", "USER", id, before, Map.of("status", "DELETED"));

        return Map.of("message", "Account deleted");
    }

    private void protectLastAdmin(
        Map<String, Object> before,
        String role,
        String status) {
        if ("ADMIN".equals(before.get("role")) && (!"ADMIN".equals(role) || !"ACTIVE".equals(status))) {
            List<Map<String, Object>> admins = db.listUsersByRoleForUpdate();
            if (admins.stream().noneMatch(a -> !a.get("id").equals(before.get("id"))))
                throw ApiException.conflict("At least one active administrator is required");
        }
    }

    public List<Map<String, Object>> tiers() {
        admin();

        return db.listSubscriptionTiers();
    }

    @Transactional
    public Map<String, Object> createTier(
        SubscriptionTierRequest body) {
        admin();
        OffsetDateTime now = OffsetDateTime.now();

        UUID id = db.insertSubscriptionTiers(
            Map.of(
                "tier_code",
                body.tierCode(),
                "display_name",
                body.displayName(),
                "price_monthly",
                body.priceMonthly(),
                "currency",
                body.currency(),
                "is_active",
                body.active(),
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        Map<String, Object> after = db.getSubscriptionTiersById(id);

        audit.record("CREATE_TIER", "SUBSCRIPTION_TIER", id, null, after);

        return after;
    }

    @Transactional
    public Map<String, Object> editTier(
        UUID id,
        SubscriptionTierRequest body) {
        admin();
        Map<String, Object> before = db.getSubscriptionTiersById(id);
        if ("FREE".equals(before.get("tier_code"))
            && (!"FREE".equals(body.tierCode()) || !body.active() || body.priceMonthly().signum() != 0))
            throw ApiException.badRequest("The free tier must remain active and free");

        db.updateSubscriptionTiersById(
            body.tierCode(),
            body.displayName(),
            body.priceMonthly(),
            body.currency(),
            body.active(),
            id
        );

        Map<String, Object> after = db.getSubscriptionTiersById(id);

        audit.record("UPDATE_TIER", "SUBSCRIPTION_TIER", id, before, after);

        return after;
    }

    @Transactional
    public List<Map<String, Object>> quotas(
        UUID id,
        @Size(max = 100)
        List<SubscriptionQuotaRequest> body) {
        admin();
        db.getSubscriptionTiersById2(id);
        if (body.stream().map(SubscriptionQuotaRequest::featureCode).distinct().count() != body.size())
            throw ApiException.badRequest("Duplicate feature quotas");

        Object before = db.listSubscriptionQuotasByTierId(id);

        for (SubscriptionQuotaRequest quota : body)
            db.updateSubscriptionQuotas(id, quota.featureCode(), quota.quotaValue(), quota.quotaPeriod());

        List<Map<String, Object>> after = db.listSubscriptionQuotasByTierId2(id);

        audit.record("UPDATE_QUOTAS", "SUBSCRIPTION_TIER", id, before, after);

        return after;
    }

    public List<Map<String, Object>> configurations() {
        admin();

        return db.listAiConfigurations();
    }

    @Transactional
    public Map<String, Object> configure(
        AiConfigurationRequest body) {
        admin();
        String json = body.configuration().toString().toLowerCase(java.util.Locale.ROOT);
        if (json.contains("api_key") || json.contains("apikey") || json.contains("secret") || json.contains("password"))
            throw ApiException.badRequest("Configure credentials through environment variables");

        db.lockAiConfigurationType(body.configType());
        int version = db.getNextAiConfigurationVersion(body.configType());

        db.updateAiConfigurationsByConfigType(body.configType());

        OffsetDateTime now = OffsetDateTime.now();

        UUID id = db.insertAiConfigurations(
            Map.of(
                "config_type",
                body.configType(),
                "provider",
                body.provider(),
                "model_name",
                body.modelName(),
                "config_json",
                db.json(body.configuration()),
                "version",
                version,
                "is_active",
                true,
                "updated_by_user_id",
                user.id(),
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        Map<String, Object> after = db.getAiConfigurationsById(id);

        audit.record("CONFIGURE_AI", "AI_CONFIGURATION", id, null, after);

        return after;
    }

    @Transactional
    public Map<String, Object> restoreConfiguration(
        UUID oldVersionId) {
        admin();

        Map<String, Object> oldConfiguration = db.getAiConfigurationsById(oldVersionId);
        String configType = (String) oldConfiguration.get("config_type");

        db.lockAiConfigurationType(configType);
        int newVersion = db.getNextAiConfigurationVersion(configType);
        db.updateAiConfigurationsByConfigType(configType);

        OffsetDateTime now = OffsetDateTime.now();
        Object configuration = oldConfiguration.get("config_json");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("config_type", configType);
        fields.put("provider", oldConfiguration.get("provider"));
        fields.put("model_name", oldConfiguration.get("model_name"));
        fields.put("config_json", configuration == null ? null : db.json(configuration));
        fields.put("version", newVersion);
        fields.put("is_active", true);
        fields.put("updated_by_user_id", user.id());
        fields.put("created_at", now);
        fields.put("updated_at", now);

        UUID newId = db.insertAiConfigurations(fields);
        Map<String, Object> restoredConfiguration = db.getAiConfigurationsById(newId);

        audit.record("RESTORE_AI_CONFIGURATION", "AI_CONFIGURATION", newId, oldConfiguration, restoredConfiguration);

        return restoredConfiguration;
    }

    public List<Map<String, Object>> prompts() {
        admin();

        return db.listAiPromptTemplates();
    }

    @Transactional
    public Map<String, Object> prompt(
        AiPromptRequest body) {
        admin();
        db.lockAiPromptFeatureCode(body.featureCode());
        int version = db.getNextAiPromptVersion(body.featureCode());

        db.updateAiPromptTemplatesByFeatureCode(body.featureCode());

        OffsetDateTime now = OffsetDateTime.now();

        UUID id = db.insertAiPromptTemplates(
            Map.of(
                "feature_code",
                body.featureCode(),
                "name",
                body.name(),
                "system_prompt",
                body.systemPrompt(),
                "user_prompt_template",
                body.userPromptTemplate(),
                "version",
                version,
                "is_active",
                true,
                "updated_by_user_id",
                user.id(),
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        Map<String, Object> after = db.getAiPromptTemplatesById(id);

        audit.record("UPDATE_PROMPT", "AI_PROMPT", id, null, after);

        return after;
    }

    @Transactional
    public Map<String, Object> restorePrompt(
        UUID oldVersionId) {
        admin();

        Map<String, Object> oldPrompt = db.getAiPromptTemplatesById(oldVersionId);
        String featureCode = (String) oldPrompt.get("feature_code");

        db.lockAiPromptFeatureCode(featureCode);
        int newVersion = db.getNextAiPromptVersion(featureCode);
        db.updateAiPromptTemplatesByFeatureCode(featureCode);

        OffsetDateTime now = OffsetDateTime.now();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("feature_code", featureCode);
        fields.put("name", oldPrompt.get("name"));
        fields.put("system_prompt", oldPrompt.get("system_prompt"));
        fields.put("user_prompt_template", oldPrompt.get("user_prompt_template"));
        fields.put("version", newVersion);
        fields.put("is_active", true);
        fields.put("updated_by_user_id", user.id());
        fields.put("created_at", now);
        fields.put("updated_at", now);

        UUID newId = db.insertAiPromptTemplates(fields);
        Map<String, Object> restoredPrompt = db.getAiPromptTemplatesById(newId);

        audit.record("RESTORE_AI_PROMPT", "AI_PROMPT", newId, oldPrompt, restoredPrompt);

        return restoredPrompt;
    }

    public List<Map<String, Object>> monitor(
        String category) {
        admin();

        return switch (category) {
            case "ai-usage" -> db.listAiUsageLogs();
            case "vector-stores" -> db.listVectorStoreMetrics();
            case "indexing-jobs" -> db.listVectorIndexingJobs();
            case "logs" -> db.listSystemLogs();
            case "audit" -> db.listAuditLogs();
            case "billing" -> db.listPaymentOrders();
            case "crawl-runs" -> db.listMarketCrawlRuns();
            default -> throw ApiException.badRequest("Unknown monitoring category");
        };
    }

    public List<Map<String, Object>> reports() {
        admin();

        return db.listPaymentReports();
    }

    @Transactional
    public Map<String, Object> resolve(
        UUID id,
        PaymentReportResolutionRequest body) {
        admin();
        Map<String, Object> before = db.getPaymentReportsById(id);

        db.updatePaymentReportsById(body.resolution(), user.id(), id);

        Map<String, Object> after = db.getPaymentReportsById(id);

        audit.record("RESOLVE_PAYMENT_REPORT", "PAYMENT_REPORT", id, before, after);

        return after;
    }
}
