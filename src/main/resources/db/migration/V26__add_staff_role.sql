-- Add STAFF as a first-class role in users.role and announcement_targets.target_role.

ALTER TABLE users
    DROP CONSTRAINT users_role_check,
    ADD CONSTRAINT users_role_check
        CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT', 'STAFF'));

ALTER TABLE announcement_targets
    DROP CONSTRAINT announcement_targets_target_role_check,
    ADD CONSTRAINT announcement_targets_target_role_check
        CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT', 'STAFF'));

