package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ResumePdfExportRepository {

    private final SqlStore db;

    public ResumePdfExportRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> resume(
        UUID id) {
        return db.one("SELECT * FROM resumes WHERE id=?", id);
    }

    public Map<String, Object> lock(
        UUID id) {
        return db.one("SELECT * FROM resumes WHERE id=? FOR UPDATE", id);
    }

    public String template(
        UUID id) {
        if (id == null) {
            return "";
        }
        Optional<Map<String, Object>> template =
            db.optional("SELECT html_content FROM resume_templates WHERE id=?", id);
        return template.map(row -> java.util.Objects.toString(row.get("html_content"), "")).orElse("");
    }

    public int save(
        UUID id,
        String key,
        String filename,
        long bytes,
        String fingerprint) {
        return db.update(
            "UPDATE resumes SET original_file_url=COALESCE(original_file_url, "
                + "CASE WHEN source='UPLOAD' AND pdf_input_hash IS NULL THEN file_url END), "
                + "file_url=?,file_name=?,mime_type='application/pdf',file_size_bytes=?,pdf_input_hash=?,"
                + "updated_at=now() WHERE id=? AND status='READY'",
            key,
            filename,
            bytes,
            fingerprint,
            id
        );
    }
}
