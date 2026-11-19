package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

@Repository
public class RequirementEvidenceRepository {

    private final SqlStore db;

    public RequirementEvidenceRepository(
        SqlStore db) {
        this.db = db;
    }

    public Optional<float[]> vector(
        UUID owner,
        String provider,
        String model,
        String hash) {
        return db.optional(
            ("SELECT embedding::text AS embedding FROM matching_evidence_vectors "
                + "WHERE user_id=? AND provider=? AND model=? AND content_hash=?"),
            owner,
            provider,
            model,
            hash
        ).map(row -> {
            String value = row.get("embedding").toString();
            String[] parts = value.substring(1, value.length() - 1).split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++)
                result[i] = Float.parseFloat(parts[i]);
            return result;
        });
    }

    public void saveVector(
        UUID owner,
        String provider,
        String model,
        String hash,
        float[] vector) {
        db.update(
            ("INSERT INTO matching_evidence_vectors(user_id,provider,model,"
                + "content_hash,embedding) VALUES(?,?,?,?,CAST(? AS vector)) ON CONFLICT " + "DO NOTHING"),
            owner,
            provider,
            model,
            hash,
            Arrays.toString(vector)
        );
    }

    public Optional<Map<String, Object>> report(
        UUID owner,
        String hash) {
        return db.optional(
            ("SELECT report FROM matching_evidence_reports WHERE user_id=? AND "
                + "input_hash=? AND created_at>now()-interval '24 hours'"),
            owner,
            hash
        ).map(row -> new LinkedHashMap<>(object(row.get("report"))));
    }

    public void saveReport(
        UUID owner,
        String hash,
        Map<String, Object> report) {
        db.update(
            ("INSERT INTO matching_evidence_reports(user_id,input_hash,report) "
                + "VALUES(?,?,?) ON CONFLICT(user_id,input_hash) DO UPDATE SET "
                + "report=EXCLUDED.report,created_at=now()"),
            owner,
            hash,
            db.json(report)
        );
    }
}
