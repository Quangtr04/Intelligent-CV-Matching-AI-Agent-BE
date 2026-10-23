package com.example.intelligent_cv_matching_ai_agent.controller.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.resume.ResumeTemplateAdminService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.resume.ResumeTemplateImportService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/v1/admin/resume-templates")
public class ResumeTemplateAdminController {

    private final ResumeTemplateAdminService service;

    private final ResumeTemplateImportService importer;

    public ResumeTemplateAdminController(
        ResumeTemplateAdminService service,
        ResumeTemplateImportService importer) {
        this.service = service;
        this.importer = importer;
    }

    @GetMapping
    public List<Map<String, Object>> list(
        @RequestParam(defaultValue = "50")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.list(limit, offset);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importDocument(
        @RequestPart("file")
        MultipartFile file,
        @RequestParam(defaultValue = "vi")
        String language) {
        return importer.importDocument(file, language);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
        @PathVariable
        UUID id) {
        return service.get(id);
    }

    @PostMapping
    public Map<String, Object> create(
        @Valid
        @RequestBody
        ResumeTemplateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ResumeTemplateRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ResumeTemplateStatusRequest request) {
        return service.status(id, request);
    }
}
