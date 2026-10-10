package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.jpa.UserJpaRepository;
import com.example.intelligent_cv_matching_ai_agent.entity.shared.identity.User;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by ProfileService. */
@Repository
public class ProfileRepository {

    private final SqlStore db;

    private final UserJpaRepository users;

    public ProfileRepository(
        SqlStore db,
        UserJpaRepository users) {
        this.db = db;
        this.users = users;
    }

    public User getProfile(
        UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Resource not found"));
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getUsersById(
        Object... args) {
        return db.one(
            ("SELECT id,email,auth_provider,full_name,avatar_url,role,status,"
                + "preferred_language,github_url,email_verified_at,last_login_at,"
                + "created_at FROM users WHERE id=?"),
            args
        );
    }

    public int updateUsersById(
        Object... args) {
        return db.update(
            ("UPDATE users SET full_name=?,avatar_url=?,"
                + "preferred_language=COALESCE(?,preferred_language),github_url=?," + "updated_at=now() WHERE id=?"),
            args
        );
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public List<Map<String, Object>> listCandidateProfileSkillsByUserId(
        Object... args) {
        return db.list(
            ("SELECT cps.skill_id,s.name,s.slug,cps.proficiency_level,"
                + "cps.years_experience FROM candidate_profile_skills cps JOIN skills s "
                + "ON s.id=cps.skill_id WHERE cps.user_id=? ORDER BY s.name"),
            args
        );
    }

    public int updateCandidateProfiles(
        Object... args) {
        return db.update(
            ("INSERT INTO candidate_profiles(user_id,created_at,updated_at) VALUES "
                + "(?,now(),now()) ON CONFLICT DO NOTHING"),
            args
        );
    }

    public int updateCandidateProfilesByUserId(
        Object... args) {
        return db.update(
            ("UPDATE candidate_profiles SET current_job_title=?,current_company=?,"
                + "target_job_title=?,career_goal=?,years_experience=?,"
                + "expected_salary_min=?,expected_salary_max=?,"
                + "salary_currency=COALESCE(?,'VND'),preferred_location=?,"
                + "workplace_preference=?,employment_type=?,profile_summary=?,"
                + "preferences_json=?,updated_at=now() WHERE user_id=?"),
            args
        );
    }

    public int deleteCandidateProfileSkillsByUserId(
        Object... args) {
        return db.update("DELETE FROM candidate_profile_skills WHERE user_id=?", args);
    }

    public Map<String, Object> getSkillsById(
        Object... args) {
        return db.one("SELECT id FROM skills WHERE id=? AND is_active=true", args);
    }

    public int updateCandidateProfileSkills(
        Object... args) {
        return db.update(
            ("INSERT INTO candidate_profile_skills(user_id,skill_id,"
                + "proficiency_level,years_experience) VALUES (?,?,?,?)"),
            args
        );
    }

    public int updateCareerProfileEmbeddingsByUserId(
        Object... args) {
        return db.update("UPDATE career_profile_embeddings SET is_current=false WHERE user_id=?", args);
    }

    public int updateRoadmapsByUserId(
        Object... args) {
        return db.update("UPDATE roadmaps SET is_outdated=true,updated_at=now() WHERE user_id=?", args);
    }

    public Object readDatabaseValue(
        Object... args) {
        return db.scalar("SELECT pg_advisory_xact_lock(hashtext('ACCOUNT_ADMIN_MEMBERSHIP'))", args);
    }

    public Map<String, Object> getUsersById2(
        Object... args) {
        return db.one("SELECT role FROM users WHERE id=?", args);
    }

    public List<Map<String, Object>> listUsersByRoleForUpdate(
        Object... args) {
        return db.list("SELECT id FROM users WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE", args);
    }

    public int updateUsersById2(
        Object... args) {
        return db.update("UPDATE users SET status='DELETED',deleted_at=now(),updated_at=now() WHERE id=?", args);
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one(
            ("SELECT password_hash,auth_provider FROM users WHERE id=? AND " + "status='ACTIVE' FOR UPDATE"),
            args
        );
    }

    public int updateUsersById3(
        Object... args) {
        return db.update("UPDATE users SET password_hash=?,updated_at=now() WHERE id=?", args);
    }

    public List<Map<String, Object>> listRefreshTokensByUserId(
        Object... args) {
        return db.list(
            ("SELECT id,created_at,expires_at FROM refresh_tokens WHERE user_id=? "
                + "AND revoked_at IS NULL AND expires_at>now() ORDER BY created_at DESC " + "LIMIT 100"),
            args
        );
    }

    public Map<String, Object> getRefreshTokensById(
        Object... args) {
        return db.one("SELECT id FROM refresh_tokens WHERE id=? AND user_id=?", args);
    }

    public int updateRefreshTokensById(
        Object... args) {
        return db.update("UPDATE refresh_tokens SET revoked_at=now() WHERE id=? AND user_id=?", args);
    }

}
