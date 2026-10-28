-- Salary preferences are one monthly amount in salary_currency, not a range.
-- Keep historical range columns untouched for recovery; they are no longer written by the profile API.
ALTER TABLE candidate_profiles
    ADD COLUMN expected_salary numeric(14,2),
    ADD CONSTRAINT candidate_profiles_expected_salary_positive
        CHECK (expected_salary IS NULL OR (expected_salary > 0 AND expected_salary <= 999999999999.99));

-- Convert each existing preference deterministically. Zero meant no preference in the old API.
-- When both positive bounds exist, use their midpoint; candidates may replace it with their exact preference.
UPDATE candidate_profiles
SET expected_salary = CASE
    WHEN expected_salary_min > 0 AND expected_salary_max > 0
        THEN round((expected_salary_min + expected_salary_max) / 2, 2)
    WHEN expected_salary_min > 0 THEN expected_salary_min
    WHEN expected_salary_max > 0 THEN expected_salary_max
    ELSE NULL
END;

COMMENT ON COLUMN candidate_profiles.expected_salary IS
    'Single positive expected monthly salary in salary_currency; NULL means no salary preference.';
COMMENT ON COLUMN candidate_profiles.expected_salary_min IS
    'Legacy pre-V12 preference retained for recovery only; not used by the current profile API.';
COMMENT ON COLUMN candidate_profiles.expected_salary_max IS
    'Legacy pre-V12 preference retained for recovery only; not used by the current profile API.';

-- Existing embeddings contain the retired range representation; regenerate from the new profile on demand.
UPDATE career_profile_embeddings SET is_current=false WHERE is_current=true;
