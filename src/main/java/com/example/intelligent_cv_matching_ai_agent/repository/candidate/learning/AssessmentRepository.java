package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AssessmentService. */
@Repository
public class AssessmentRepository {

    private final SqlStore db;

    public AssessmentRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getAssessmentSessionsById(
        Object... args) {
        return db.one("SELECT * FROM assessment_sessions WHERE id=? AND user_id=?", args);
    }

    public List<Map<String, Object>> listQuestionsForSession(
        UUID sessionId,
        boolean completed) {
        if (completed) {
            return db.list("SELECT * FROM assessment_questions WHERE session_id=? ORDER BY sequence_order", sessionId);
        }
        return db.list(
            ("SELECT id,session_id,sequence_order,part,question_text,options,"
                + "related_skill_name FROM assessment_questions WHERE session_id=? ORDER " + "BY sequence_order"),
            sessionId
        );
    }

    public List<Map<String, Object>> listAssessmentAnswersBySessionId(
        Object... args) {
        return db.list(
            ("SELECT question_id,selected_option,answered_at FROM " + "assessment_answers WHERE session_id=?"),
            args
        );
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one(
            "SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE' "
                + "AND (valid_through IS NULL OR valid_through>now())",
            args
        );
    }

    public List<Map<String, Object>> listMarketJobSkills(
        UUID jobId) {
        return db.list(
            "SELECT skill_id,skill_name,normalized_skill,is_required FROM market_job_skills "
                + "WHERE market_job_id=? ORDER BY is_required DESC,normalized_skill",
            jobId
        );
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT target_job_title FROM candidate_profiles WHERE user_id=?", args);
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId2(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public int updateAssessmentSessionsByUserId(
        Object... args) {
        return db.update("UPDATE assessment_sessions SET is_current=false WHERE user_id=? AND market_job_id=?", args);
    }

    public UUID insertAssessmentSessions(
        Map<String, Object> fields) {
        return db.insert("assessment_sessions", fields);
    }

    public UUID insertAssessmentQuestions(
        Map<String, Object> fields) {
        return db.insert("assessment_questions", fields);
    }

    public Map<String, Object> getAssessmentSessionsByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM assessment_sessions WHERE id=? AND user_id=? FOR UPDATE", args);
    }

    public Map<String, Object> getAssessmentQuestionsById(
        Object... args) {
        return db.one("SELECT id FROM assessment_questions WHERE id=? AND session_id=?", args);
    }

    public int updateAssessmentAnswers(
        Object... args) {
        return db.update(
            ("INSERT INTO assessment_answers(session_id,question_id,selected_option,"
                + "answered_at) VALUES(?,?,?,now()) ON CONFLICT(session_id,question_id) "
                + "DO UPDATE SET selected_option=EXCLUDED.selected_option," + "answered_at=now()"),
            args
        );
    }

    public Object countAssessmentQuestionsBySessionId(
        Object... args) {
        return db.scalar(
            ("SELECT count(*) FROM assessment_questions q LEFT JOIN "
                + "assessment_answers a ON a.question_id=q.id AND "
                + "a.session_id=q.session_id WHERE q.session_id=? AND a.id IS NULL"),
            args
        );
    }

    public int updateAssessmentAnswersByQuestionId(
        Object... args) {
        return db.update(
            ("UPDATE assessment_answers a SET " + "is_correct=(a.selected_option=q.correct_option) FROM "
                + "assessment_questions q WHERE a.question_id=q.id AND a.session_id=?"),
            args
        );
    }

    public List<Map<String, Object>> listAssessmentQuestionsByIsCorrect(
        Object... args) {
        return db.list(
            ("SELECT COALESCE(NULLIF(q.related_skill_name,''),'General') AS skill,"
                + "count(*) AS total,count(*) FILTER(WHERE a.is_correct) AS correct,"
                + "round(100.0*count(*) FILTER(WHERE a.is_correct)/count(*),2) AS score "
                + "FROM assessment_questions q JOIN assessment_answers a ON "
                + "a.question_id=q.id AND a.session_id=q.session_id WHERE q.session_id=? "
                + "GROUP BY COALESCE(NULLIF(q.related_skill_name,''),'General') ORDER BY " + "skill"),
            args
        );
    }

    public int updateAssessmentSessionsById(
        Object... args) {
        return db.update(
            ("UPDATE assessment_sessions SET status='completed',submitted_at=now()," + "skill_scores=? WHERE id=?"),
            args
        );
    }

    public int updateRoadmapsByUserId(
        Object... args) {
        return db.update(
            ("UPDATE roadmaps SET is_outdated=true,updated_at=now() WHERE user_id=? "
                + "AND market_job_id IS NOT DISTINCT FROM CAST(? AS uuid)"),
            args
        );
    }

}
