package com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume;

/**
 * Download content and response metadata; HTTP headers are assembled by the
 * controller.
 */
public record ResumeDownloadResponse(
    byte[] content,
    String filename,
    String mimeType
) {
}
