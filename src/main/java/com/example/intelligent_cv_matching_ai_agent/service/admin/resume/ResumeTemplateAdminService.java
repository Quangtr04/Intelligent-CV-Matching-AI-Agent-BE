package com.example.intelligent_cv_matching_ai_agent.service.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.resume.ResumeTemplateAdminRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResumeTemplateAdminService {

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
        user.requireOperationsRole();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) {
            throw ApiException.badRequest("limit must be 1..100 and offset must be 0..100000");
        }
        return db.list(limit, offset);
    }

    public Map<String, Object> get(
        UUID id) {
        user.requireOperationsRole();
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> create(
        ResumeTemplateRequest request) {
        user.requireOperationsRole();
        UUID id = db.create(normalized(request));
        Map<String, Object> after = db.get(id);
        audit.record("CREATE_RESUME_TEMPLATE", "RESUME_TEMPLATE", id, null, after);
        return after;
    }

    @Transactional
    public Map<String, Object> update(
        UUID id,
        ResumeTemplateRequest request) {
        user.requireOperationsRole();
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
        user.requireOperationsRole();
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

    private Map<String, Object> normalized(
        ResumeTemplateRequest request) {
        if (request == null || request.active() == null || request.trialEnabled() == null) {
            throw ApiException.badRequest("active and trialEnabled must be booleans");
        }
        if (!Set.of("vi", "en").contains(request.language() == null ? "" : request.language())) {
            throw ApiException.badRequest("language must be vi or en");
        }
        String html = ResumeTemplateHtml.normalize(request.htmlContent());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", text(request.name(), "name", 150, true));
        String careerPath = text(request.careerPath(), "careerPath", 150, false);
        values.put("career_path", careerPath.isEmpty() ? null : careerPath);
        values.put("language", request.language());
        values.put("description", text(request.description(), "description", 2000, false));
        values.put("is_active", request.active());
        values.put("is_trial_enabled", request.trialEnabled());
        values.put("html_content", html);
        return values;
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
