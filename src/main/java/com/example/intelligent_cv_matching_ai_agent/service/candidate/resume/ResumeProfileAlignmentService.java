package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeProfileAlignmentRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeProfileAlignmentRefreshRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumeProfileAlignmentService {

    private final ResumeProfileAlignmentRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final ObjectMapper mapper;

    private final ResumeProfileAlignmentRefreshRepository refreshes;

    public ResumeProfileAlignmentService(
        ResumeProfileAlignmentRepository db,
        CurrentUser user,
        AiService ai,
        ObjectMapper mapper,
        ResumeProfileAlignmentRefreshRepository refreshes) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.mapper = mapper;
        this.refreshes = refreshes;
    }

    @Transactional
    public Map<String, Object> create(
        UUID resumeId) {
        user.requireRole("CANDIDATE");
        UUID owner = user.id();
        return evaluate(resumeId, owner, null);
    }

    /** Internal worker entry point: ownership is read from the durable request, never from an HTTP body. */
    @Transactional
    public Map<String, Object> createForRefresh(
        UUID resumeId,
        UUID owner,
        UUID token,
        long generation) {
        if (!refreshes.isActiveCandidate(owner)) {
            throw ApiException.forbidden("Automatic comparisons require an active candidate account");
        }
        return evaluate(resumeId, owner, new Refresh(token, generation));
    }

    @Transactional(readOnly = true)
    public String profileFingerprint(
        UUID owner) {
        return hash(profileSnapshot(db.profile(owner).orElse(Map.of()), owner));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> refreshStatus(
        UUID resumeId) {
        user.requireRole("CANDIDATE");
        UUID owner = user.id();
        db.resume(resumeId, owner);
        return refreshes.status(resumeId, owner);
    }

    private Map<String, Object> evaluate(
        UUID resumeId,
        UUID owner,
        Refresh refresh) {
        Map<String, Object> resume = db.resume(resumeId, owner);
        if (refresh == null && refreshes.active(resumeId, owner)) {
            throw ApiException
                .conflict("An automatic CV/profile comparison is queued or running. Wait for its result.");
        }
        requireReady(resume);
        Map<String, Object> profile = db.profile(owner)
            .orElseThrow(() -> ApiException.conflict("Complete your career profile before comparing it with a CV"));
        Map<String, Object> profileSnapshot = profileSnapshot(profile, owner);
        if (!ResumeProfileAlignment.hasComparableProfile(profileSnapshot)) {
            throw ApiException.conflict("Complete your career profile before comparing it with a CV");
        }
        Map<String, Object> resumeSnapshot = freeze(ResumeProfileAlignment.resumeSnapshot(resume));
        if (ResumeProfileAlignment.cvEvidenceOptions(resumeSnapshot).isEmpty()) {
            throw ApiException
                .badRequest("Complete the CV with readable content before comparing it with your profile");
        }
        String profileHash = hash(profileSnapshot);
        String resumeHash = contentHash(resume, resumeSnapshot);
        String resumeSnapshotHash = hash(resumeSnapshot);
        Map<String, Object> existing = null;
        if (refresh != null) {
            requireRefresh(resumeId, owner, refresh, profileHash, false);
            existing = db.list(resumeId, owner, 1, 0).stream()
                .filter(row -> profileHash.equals(row.get("profile_hash")))
                .filter(row -> resumeHash.equals(row.get("resume_content_hash")))
                .filter(row -> resumeSnapshotHash.equals(hash(row.get("resume_snapshot")))).findFirst().orElse(null);
        }
        if (existing == null && !ai.enabled()) {
            throw new ApiException(
                503,
                "AI is disabled. Enable the self-hosted Ollama model to compare CV and profile."
            );
        }

        // No user/profile/resume row lock is held while inference reserves quota in its own transaction.
        Map<String, Object> input = Map.of(
            "resume",
            resumeSnapshot,
            "career_profile",
            profileSnapshot,
            "language",
            Objects.toString(resume.get("language"), "vi")
        );
        Map<String, Object> generated = existing != null
            ? Map.of()
            : refresh == null
                ? ai.generate("CV_PROFILE_ALIGNMENT", input)
                : ai.generateForUser(owner, "CV_PROFILE_ALIGNMENT", input);

        // The profile update workflow locks its profile row before editing its skill rows.
        // Re-read only after inference, then retain these locks through the immutable history insert.
        Map<String, Object> currentProfile = db.lockProfile(owner).orElseThrow(ResumeProfileAlignmentService::changed);
        Map<String, Object> currentResume =
            db.lockResume(resumeId, owner).orElseThrow(ResumeProfileAlignmentService::changed);
        Map<String, Object> currentResumeSnapshot = freeze(ResumeProfileAlignment.resumeSnapshot(currentResume));
        if (!"READY".equals(currentResume.get("status"))
            || !profileHash.equals(hash(profileSnapshot(currentProfile, owner)))
            || !resumeHash.equals(contentHash(currentResume, currentResumeSnapshot))
            || !resumeSnapshotHash.equals(hash(currentResumeSnapshot))) {
            throw changed();
        }
        if (refresh != null) {
            requireRefresh(resumeId, owner, refresh, profileHash, true);
            if (!refreshes.isActiveCandidate(owner)) {
                throw ApiException.forbidden("The candidate account is no longer active");
            }
        }
        if (existing != null) {
            completeRefresh(resumeId, owner, refresh, (UUID) existing.get("id"));
            return expose(existing, currentResume, profileSnapshot);
        }
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resumeSnapshot, profileSnapshot);
        String model = Objects.toString(generated.get("_model"), ai.model());
        if (model == null || model.isBlank() || model.length() > 255) {
            throw new ApiException(502, "AI alignment returned an invalid model identifier");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("resume_id", resumeId);
        values.put("user_id", owner);
        values.put("resume_content_hash", resumeHash);
        values.put("profile_hash", profileHash);
        values.put("profile_snapshot", db.json(profileSnapshot));
        values.put("resume_snapshot", db.json(resumeSnapshot));
        values.put("result_json", db.json(result));
        values.put("model_name", model);
        UUID alignment = db.insert(values);
        if (refresh == null) {
            refreshes.completeManual(resumeId, owner, profileHash, alignment);
        } else {
            completeRefresh(resumeId, owner, refresh, alignment);
        }
        return expose(db.get(alignment, resumeId, owner), currentResume, profileSnapshot);
    }

    private void requireRefresh(
        UUID resume,
        UUID owner,
        Refresh refresh,
        String profileHash,
        boolean lock) {
        Map<String, Object> request =
            refreshes.claim(resume, owner, refresh.token(), lock).orElseThrow(ResumeProfileAlignmentService::changed);
        if (((Number) request.get("generation")).longValue() != refresh.generation()
            || !profileHash.equals(request.get("requested_profile_hash"))) {
            throw changed();
        }
    }

    private void completeRefresh(
        UUID resume,
        UUID owner,
        Refresh refresh,
        UUID alignment) {
        if (refreshes.complete(resume, owner, refresh.token(), refresh.generation(), alignment) != 1) {
            throw changed();
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> list(
        UUID resumeId,
        int limit,
        int offset) {
        user.requireRole("CANDIDATE");
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) {
            throw ApiException.badRequest("limit must be 1..100 and offset must be 0..100000");
        }
        UUID owner = user.id();
        Map<String, Object> resume = db.resume(resumeId, owner);
        Map<String, Object> profile = profileSnapshot(db.profile(owner).orElse(Map.of()), owner);
        return db.list(resumeId, owner, limit, offset).stream().map(row -> expose(row, resume, profile)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> get(
        UUID resumeId,
        UUID alignmentId) {
        user.requireRole("CANDIDATE");
        UUID owner = user.id();
        Map<String, Object> resume = db.resume(resumeId, owner);
        Map<String, Object> profile = profileSnapshot(db.profile(owner).orElse(Map.of()), owner);
        return expose(db.get(alignmentId, resumeId, owner), resume, profile);
    }

    private Map<String, Object> expose(
        Map<String, Object> row,
        Map<String, Object> resume,
        Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        Map<String, Object> currentResume = freeze(ResumeProfileAlignment.resumeSnapshot(resume));
        boolean resumeChanged = !Objects.equals(row.get("resume_content_hash"), contentHash(resume, currentResume))
            || !hash(row.get("resume_snapshot")).equals(hash(currentResume));
        boolean profileChanged = !Objects.equals(row.get("profile_hash"), hash(profile));
        result.put("resume_changed", resumeChanged);
        result.put("profile_changed", profileChanged);
        result.put("is_stale", resumeChanged || profileChanged);
        return result;
    }

    private Map<String, Object> profileSnapshot(
        Map<String, Object> profile,
        UUID owner) {
        if (profile.isEmpty()) {
            return Map.of();
        }
        return freeze(ResumeProfileAlignment.snapshotProfile(profile, db.profileSkills(owner)));
    }

    private static void requireReady(
        Map<String, Object> resume) {
        if (!"READY".equals(resume.get("status"))) {
            throw ApiException.conflict("Confirm CV data before comparing it with your career profile");
        }
    }

    private static ApiException changed() {
        return ApiException
            .conflict("CV or career profile changed during evaluation. Review the current data and retry.");
    }

    private String contentHash(
        Map<String, Object> resume,
        Map<String, Object> snapshot) {
        String stored = Objects.toString(resume.get("content_hash"), "");
        return stored.matches("[0-9a-f]{64}") ? stored : hash(snapshot);
    }

    private String hash(
        Object value) {
        return FlowSupport.hash(mapper.writeValueAsString(canonical(value)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(
        Map<String, Object> source) {
        return (Map<String, Object>) canonical(source);
    }

    private static Object canonical(
        Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach(
                (
                    key,
                    item) -> result.put(key.toString(), canonical(item))
            );
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ResumeProfileAlignmentService::canonical).toList();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }

    private record Refresh(
        UUID token,
        long generation
    ) {
    }
}
