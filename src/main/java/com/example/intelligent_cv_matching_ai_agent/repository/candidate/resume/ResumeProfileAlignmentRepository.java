package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ResumeProfileAlignmentRepository {

    private final SqlStore db;

    public ResumeProfileAlignmentRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> resume(
        UUID id,
        UUID owner) {
        return db.one("SELECT * FROM resumes WHERE id=? AND user_id=?", id, owner);
    }

    public Optional<Map<String, Object>> lockResume(
        UUID id,
        UUID owner) {
        return db.optional("SELECT * FROM resumes WHERE id=? AND user_id=? FOR UPDATE", id, owner);
    }

    public Optional<Map<String, Object>> profile(
        UUID owner) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", owner);
    }

    public Optional<Map<String, Object>> lockProfile(
        UUID owner) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=? FOR UPDATE", owner);
    }

    public List<Map<String, Object>> profileSkills(
        UUID owner) {
        return db.list(
            "SELECT cps.skill_id,s.name,cps.proficiency_level,cps.years_experience"
                + " FROM candidate_profile_skills cps JOIN skills s ON s.id=cps.skill_id"
                + " WHERE cps.user_id=? ORDER BY s.name,cps.skill_id",
            owner
        );
    }

    public UUID insert(
        Map<String, Object> values) {
        return db.insert("resume_profile_alignments", values);
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> list(
        UUID resume,
        UUID owner,
        int limit,
        int offset) {
        return db.list(
            "SELECT * FROM resume_profile_alignments WHERE resume_id=? AND user_id=?"
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            resume,
            owner,
            limit,
            offset
        );
    }

    public Map<String, Object> get(
        UUID id,
        UUID resume,
        UUID owner) {
        return db
            .one("SELECT * FROM resume_profile_alignments WHERE id=? AND resume_id=? AND user_id=?", id, resume, owner);
    }
}
