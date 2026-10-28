package com.example.intelligent_cv_matching_ai_agent.util.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Text processing and safe error messages for RAG document indexing. */
public final class RagDocumentSupport {

    private RagDocumentSupport() {
    }

    public static List<String> split(
        String content) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(content.length(), start + 4000);

            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
                end--;
            }

            result.add(content.substring(start, end));

            if (end == content.length()) {
                break;
            }

            start = Math.max(start + 1, end - 300);

            if (start > 0 && Character.isLowSurrogate(content.charAt(start))) {
                start--;
            }
        }

        return result;
    }

    public static String safeError(
        RuntimeException ex) {
        String value = ex instanceof ApiException ? ex.getMessage() : "Embedding index operation failed";

        return value == null ? "Index operation failed" : value.substring(0, Math.min(1000, value.length()));
    }
}
