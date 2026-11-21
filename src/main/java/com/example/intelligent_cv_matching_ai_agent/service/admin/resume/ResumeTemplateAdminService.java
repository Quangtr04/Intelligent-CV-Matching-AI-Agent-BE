package com.example.intelligent_cv_matching_ai_agent.service.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplatePackagesRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.resume.ResumeTemplateAdminRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResumeTemplateAdminService {

    private static final List<String> DEFAULT_TIER_CODES = List.of("FREE", "BASIC", "PRO");

    private final ResumeTemplateAdminRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    public ResumeTemplateAdminService(
        ResumeTemplateAdminRepository db,
        CurrentUser user,
        AuditService audit) {
        this.db = db;
        this.user = user;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(
        int limit,
        int offset) {
        user.requireStaffOrOperationsRole();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) {
            throw ApiException.badRequest("limit must be 1..100 and offset must be 0..100000");
        }
        return db.list(limit, offset);
    }

    public Map<String, Object> get(
        UUID id) {
        user.requireStaffOrOperationsRole();
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> create(
        ResumeTemplateRequest request) {
        user.requireStaffOrOperationsRole();
        UUID id = db.create(normalized(request));
        Map<String, Object> after = db.get(id);
        audit.record("CREATE_RESUME_TEMPLATE", "RESUME_TEMPLATE", id, null, after);
        return after;
    }

    @Transactional
    public Map<String, Object> update(
        UUID id,
        ResumeTemplateRequest request) {
        user.requireStaffOrOperationsRole();
        Map<String, Object> values = normalized(request);
        Map<String, Object> before = db.lock(id);
        db.update(id, values);
        Map<String, Object> after = db.get(id);
        audit.record("UPDATE_RESUME_TEMPLATE", "RESUME_TEMPLATE", id, before, after);
        return after;
    }

    @Transactional
    public Map<String, Object> status(
        UUID id,
        ResumeTemplateStatusRequest request) {
        user.requireStaffOrOperationsRole();
        if (request == null || request.active() == null) {
            throw ApiException.badRequest("active must be a boolean");
        }
        Map<String, Object> before = db.lock(id);
        if (request.active().equals(before.get("is_active"))) {
            return before;
        }
        db.status(id, request.active());
        Map<String, Object> after = db.get(id);
        audit.record("UPDATE_RESUME_TEMPLATE_STATUS", "RESUME_TEMPLATE", id, before, after);
        return after;
    }

    @Transactional
    public Map<String, Object> packages(
        UUID id,
        ResumeTemplatePackagesRequest request) {
        user.requireStaffOrOperationsRole();
        if (request == null || request.allowedTierCodes() == null || request.allowedTierCodes().isEmpty()) {
            throw ApiException.badRequest("allowedTierCodes must contain at least one subscription tier code");
        }
        List<String> tiers = normalizeTierCodes(request.allowedTierCodes(), true);
        Map<String, Object> before = db.lock(id);
        db.packages(id, tiers);
        Map<String, Object> after = db.get(id);
        audit.record("UPDATE_RESUME_TEMPLATE_PACKAGES", "RESUME_TEMPLATE", id, before, after);
        return after;
    }

    private Map<String, Object> normalized(
        ResumeTemplateRequest request) {
        if (request == null || request.active() == null || request.trialEnabled() == null) {
            throw ApiException.badRequest("active and trialEnabled must be booleans");
        }
        if (!Set.of("vi", "en").contains(request.language() == null ? "" : request.language())) {
            throw ApiException.badRequest("language must be vi or en");
        }
        String html = ResumeTemplateHtml.normalize(request.htmlContent());
        List<String> tiers = normalizeTierCodes(request.allowedTierCodes(), false);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", text(request.name(), "name", 150, true));
        String careerPath = text(request.careerPath(), "careerPath", 150, false);
        values.put("career_path", careerPath.isEmpty() ? null : careerPath);
        values.put("language", request.language());
        values.put("description", text(request.description(), "description", 2000, false));
        values.put("is_active", request.active());
        values.put("is_trial_enabled", request.trialEnabled());
        values.put("html_content", html);
        values.put("allowed_tier_codes", tiers);
        return values;
    }

    private List<String> normalizeTierCodes(
        List<String> codes,
        boolean required) {
        if (codes == null) {
            if (required) {
                throw ApiException.badRequest("allowedTierCodes must contain at least one subscription tier code");
            }
            return DEFAULT_TIER_CODES;
        }
        if (codes.isEmpty() || codes.size() > 50) {
            throw ApiException.badRequest("allowedTierCodes must contain between 1 and 50 tier codes");
        }
        List<String> known = db.subscriptionTierCodes();
        Set<String> validCodes =
            known == null || known.isEmpty() ? Set.copyOf(DEFAULT_TIER_CODES) : new LinkedHashSet<>(known);
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : codes) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("Subscription tier code must not be blank");
            }
            String code = raw.strip().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z0-9_]{1,30}") || !validCodes.contains(code)) {
                throw ApiException.badRequest("Unsupported subscription tier code: " + code);
            }
            normalized.add(code);
        }
        return new ArrayList<>(normalized);
    }

    private String text(
        String value,
        String field,
        int maximum,
        boolean required) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw ApiException.badRequest(field + " must not contain a NUL character");
        }
        String normalized = value == null ? "" : value.trim();
        if ((required && normalized.isEmpty()) || normalized.length() > maximum) {
            throw ApiException.badRequest(
                field + " must " + (required ? "be present and " : "") + "contain at most " + maximum + " characters"
            );
        }
        return normalized;
    }
}
