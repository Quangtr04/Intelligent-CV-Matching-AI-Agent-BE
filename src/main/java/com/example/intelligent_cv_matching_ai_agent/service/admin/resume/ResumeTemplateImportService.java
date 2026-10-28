package com.example.intelligent_cv_matching_ai_agent.service.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateDocumentConverter;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Converts an uploaded source into a reviewable draft; only the existing explicit save creates a template. */
@Service
public class ResumeTemplateImportService {

    private final CurrentUser user;

    private final ResumeTemplateDocumentConverter converter;

    public ResumeTemplateImportService(
        CurrentUser user,
        ResumeTemplateDocumentConverter converter) {
        this.user = user;
        this.converter = converter;
    }

    public Map<String, Object> importDocument(
        MultipartFile file,
        String language) {
        // Authorize before reading or parsing any uploaded bytes.
        user.requireRole("ADMIN");
        if (!Set.of("vi", "en").contains(language == null ? "" : language)) {
            throw ApiException.badRequest("language must be vi or en");
        }
        Map<String, Object> draft = new LinkedHashMap<>(converter.convert(file, language));
        Object html = draft.get("html_content");
        if (!(html instanceof String source)) {
            throw new IllegalStateException("Document conversion did not produce an HTML draft");
        }
        draft.put("html_content", ResumeTemplateHtml.normalizeDraft(source));
        draft.put("requires_review", true);
        return draft;
    }
}
