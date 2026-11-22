package com.example.intelligent_cv_matching_ai_agent.repository.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ResumeTemplateAdminRepository {

    private final SqlStore db;

    public ResumeTemplateAdminRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(
        int limit,
        int offset) {
        return db
            .list("SELECT * FROM resume_templates ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", limit, offset);
    }

    public Map<String, Object> get(
        UUID id) {
        return db.one("SELECT * FROM resume_templates WHERE id=?", id);
    }

    public Map<String, Object> lock(
        UUID id) {
        return db.one("SELECT * FROM resume_templates WHERE id=? FOR UPDATE", id);
    }

    public UUID create(
        Map<String, Object> values) {
        return (UUID) db.scalar(
            ("INSERT INTO resume_templates(name,career_path,language,description,is_active,is_trial_enabled,"
                + "html_content,allowed_tier_codes) VALUES(?,?,?,?,?,?,?,?) RETURNING id"),
            values.get("name"),
            values.get("career_path"),
            values.get("language"),
            values.get("description"),
            values.get("is_active"),
            values.get("is_trial_enabled"),
            values.get("html_content"),
            db.json(values.getOrDefault("allowed_tier_codes", List.of("FREE", "BASIC", "PRO")))
        );
    }

    public void update(
        UUID id,
        Map<String, Object> values) {
        db.update(
            ("UPDATE resume_templates SET name=?,career_path=?,language=?,description=?,is_active=?,"
                + "is_trial_enabled=?,html_content=?,allowed_tier_codes=?,updated_at=now() WHERE id=?"),
            values.get("name"),
            values.get("career_path"),
            values.get("language"),
            values.get("description"),
            values.get("is_active"),
            values.get("is_trial_enabled"),
            values.get("html_content"),
            db.json(values.getOrDefault("allowed_tier_codes", List.of("FREE", "BASIC", "PRO"))),
            id
        );
    }

    public void status(
        UUID id,
        boolean active) {
        db.update("UPDATE resume_templates SET is_active=?,updated_at=now() WHERE id=?", active, id);
    }

    public void packages(
        UUID id,
        List<String> tierCodes) {
        db.update(
            "UPDATE resume_templates SET allowed_tier_codes=?,updated_at=now() WHERE id=?",
            db.json(tierCodes),
            id
        );
    }

    public List<String> subscriptionTierCodes() {
        return db.list("SELECT tier_code FROM subscription_tiers WHERE tier_type='SUBSCRIPTION' ORDER BY price_monthly")
            .stream().map(row -> String.valueOf(row.get("tier_code"))).toList();
    }
}
