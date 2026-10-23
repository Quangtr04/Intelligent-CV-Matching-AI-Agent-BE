package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingSalarySettingsRepository {

    private final SqlStore db;

    public MatchingSalarySettingsRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> get() {
        return db.optional("SELECT * FROM matching_salary_settings WHERE id=1")
            .orElseThrow(() -> new ApiException(503, "Matching salary settings are unavailable"));
    }

    public Map<String, Object> lock() {
        return db.one("SELECT * FROM matching_salary_settings WHERE id=1 FOR UPDATE");
    }

    public void update(
        BigDecimal tolerancePercent,
        UUID actor) {
        db.update(
            "UPDATE matching_salary_settings SET tolerance_percent=?,version=version+1,"
                + "updated_at=now(),updated_by=? WHERE id=1",
            tolerancePercent,
            actor
        );
    }
}
