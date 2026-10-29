package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.SavedJobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.offset;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.pageSize;

/** Candidate wishlist of jobs found in job discovery. */
@Service
public class SavedJobService {

    private final SavedJobRepository db;

    private final CurrentUser user;

    public SavedJobService(
        SavedJobRepository db,
        CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    public List<Map<String, Object>> list(
        int limit,
        int offset) {
        user.requireRole("CANDIDATE");

        return db.list(user.id(), pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> ids() {
        user.requireRole("CANDIDATE");

        return db.listIds(user.id());
    }

    @Transactional
    public Map<String, Object> save(
        UUID jobId) {
        user.requireRole("CANDIDATE");
        if (!db.activeJobExists(jobId))
            throw ApiException.notFound("Resource not found");
        var saved = db.save(user.id(), jobId);

        return Map.of("job_id", jobId, "saved", true, "saved_at", saved.get("saved_at"));
    }

    @Transactional
    public Map<String, Object> remove(
        UUID jobId) {
        user.requireRole("CANDIDATE");
        db.remove(user.id(), jobId);

        return Map.of("job_id", jobId, "saved", false);
    }
}
