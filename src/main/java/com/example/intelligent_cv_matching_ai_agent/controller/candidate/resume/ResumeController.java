package com.example.intelligent_cv_matching_ai_agent.controller.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Valid;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.AISupportRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.resume.ResumeWriteRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeController {

    private final ResumeService service;

    private final ResumePdfExportService pdfExport;

    public ResumeController(
        ResumeService service) {
        this(service, null);
    }

    @Autowired
    public ResumeController(
        ResumeService service,
        ResumePdfExportService pdfExport) {
        this.service = service;
        this.pdfExport = pdfExport;
    }

    @GetMapping("/templates")
    public Object templates() {
        return service.templates();
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Object get(
        @PathVariable
        UUID id) {
        return service.get(id);
    }

    @PostMapping
    public Object create(
        @Valid
        @RequestBody
        ResumeWriteRequest body) {
        return service.create(body.toInputMap(), false);
    }

    @PostMapping("/build")
    public Object build(
        @Valid
        @RequestBody
        ResumeWriteRequest body) {
        return service.create(body.toInputMap(), true);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(
        @RequestPart
        MultipartFile file,
        @RequestParam(required = false)
        String title,
        @RequestParam(defaultValue = "vi")
        String language) {
        return service.upload(file, title, language);
    }

    @PatchMapping("/{id}")
    public Object update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ResumeWriteRequest body) {
        return service.update(id, body.toInputMap());
    }

    @PostMapping("/{id}/confirm")
    public Object confirm(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody(required = false)
        ResumeWriteRequest body) {
        return service.confirm(id, body == null ? Map.of() : body.toInputMap());
    }

    @PostMapping("/{id}/primary")
    public Object primary(
        @PathVariable
        UUID id) {
        return service.primary(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
        @PathVariable
        UUID id) {
        service.archive(id);
    }

    @PostMapping("/{id}/analyses")
    public Object analyze(
        @PathVariable
        UUID id) {
        return service.analyze(id);
    }

    @GetMapping("/{id}/analyses")
    public Object analyses(
        @PathVariable
        UUID id) {
        return service.analyses(id);
    }

    @GetMapping("/{id}/analyses/{analysisId}")
    public Object analysis(
        @PathVariable
        UUID id,
        @PathVariable
        UUID analysisId) {
        return service.analysis(analysisId, id);
    }

    @PostMapping("/{id}/embedding")
    public Object embed(
        @PathVariable
        UUID id) {
        return service.embedding(id);
    }

    @PostMapping("/{id}/export-pdf")
    public Object exportPdf(
        @PathVariable
        UUID id) {
        return pdfExport.export(id);
    }

    @GetMapping("/{id}/export-pdf/download")
    public ResponseEntity<byte[]> downloadPdf(
        @PathVariable
        UUID id) {
        ResumeDownloadResponse file = pdfExport.download(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString()
            ).header("X-Content-Type-Options", "nosniff").body(file.content());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(
        @PathVariable
        UUID id,
        @RequestParam(defaultValue = "false")
        boolean text) {
        ResumeDownloadResponse file = service.download(id, text);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString()
            ).header("X-Content-Type-Options", "nosniff").body(file.content());
    }

    @PostMapping({"AI-support", "ai-support"})
    public Object aiSupport(
        @Valid
        @RequestBody
        AISupportRequest body) {
        return service.aiSupport(body);
    }
}
