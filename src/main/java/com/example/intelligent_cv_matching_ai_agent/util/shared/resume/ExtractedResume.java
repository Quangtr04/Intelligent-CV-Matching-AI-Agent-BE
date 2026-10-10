package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

public record ExtractedResume(
    byte[] bytes,
    String filename,
    String mimeType,
    String text,
    boolean ocrUsed,
    String parser
) {
}
