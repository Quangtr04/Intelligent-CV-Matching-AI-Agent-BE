-- Run against an isolated migrated database with: psql -v ON_ERROR_STOP=1 -f scripts/schema-smoke.sql
-- Every fixture and helper rolls back. This exercises relational constraints,
-- not vector semantics; use the production pgvector database for vector tests.
BEGIN;

CREATE FUNCTION pg_temp.expect_constraint(statement text, expected_state text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = expected_state THEN RETURN; END IF;
        RAISE;
    END;
    RAISE EXCEPTION 'Expected SQLSTATE % for statement: %', expected_state, statement;
END;
$$;

DO $$
DECLARE
    candidate_id uuid := gen_random_uuid();
    guest_id uuid := gen_random_uuid();
    resume_id uuid := gen_random_uuid();
    free_tier uuid;
    job_id uuid := gen_random_uuid();
    run_id uuid := gen_random_uuid();
    session_one uuid := gen_random_uuid();
    session_two uuid := gen_random_uuid();
    question_id uuid := gen_random_uuid();
    order_id uuid := gen_random_uuid();
    node_id uuid := gen_random_uuid();
    roadmap_id uuid := gen_random_uuid();
    other_roadmap_id uuid := gen_random_uuid();
    other_node_id uuid := gen_random_uuid();
BEGIN
    IF to_regclass('public.rag_query_logs') IS NOT NULL
        OR to_regclass('public.skill_prerequisites') IS NOT NULL
        OR to_regclass('public.career_profile_embeddings') IS NOT NULL
        OR to_regclass('public.resume_embeddings') IS NOT NULL THEN
        RAISE EXCEPTION 'Unused application tables should have been removed by V45';
    END IF;

    SELECT id INTO STRICT free_tier FROM subscription_tiers WHERE tier_code = 'FREE';
    INSERT INTO users (id, email, full_name, password_hash, status)
        VALUES (candidate_id, candidate_id::text || '@example.invalid', 'Schema smoke candidate', 'test-only-unusable-hash', 'ACTIVE');
    INSERT INTO guest_sessions (id, session_key, token_limit) VALUES (guest_id, guest_id::text, 100);
    INSERT INTO resumes (id, user_id, title, source, status, is_primary) VALUES (resume_id, candidate_id, 'Schema smoke CV', 'MANUAL', 'READY', true);
    INSERT INTO market_job_posts (id, source_site, raw_content_hash, job_title, role_category, raw_content)
        VALUES (job_id, 'SCHEMA_TEST', job_id::text, 'Schema smoke job', 'BACKEND', 'Schema smoke fixture');
    INSERT INTO job_matching_runs (id, user_id, basis) VALUES (run_id, candidate_id, 'SELECTED_CV');
    INSERT INTO user_subscriptions (user_id, tier_id) VALUES (candidate_id, free_tier);
    INSERT INTO assessment_sessions (id, user_id) VALUES (session_one, candidate_id), (session_two, candidate_id);
    INSERT INTO assessment_questions (id, session_id, sequence_order, part, question_text, options)
        VALUES (question_id, session_one, 1, 1, 'Smoke question', '{"A":"Answer"}');
    INSERT INTO payment_orders (id, user_id, tier_id, duration_months, amount, payment_provider)
        VALUES (order_id, candidate_id, free_tier, 1, 0, 'SCHEMA_TEST');
    INSERT INTO roadmaps (id, user_id, title) VALUES (roadmap_id, candidate_id, 'Schema smoke roadmap');
    INSERT INTO roadmap_nodes (id, roadmap_id, skill_name, sequence_order) VALUES (node_id, roadmap_id, 'Java', 1);
    INSERT INTO roadmaps (id, user_id, title) VALUES (other_roadmap_id, candidate_id, 'Other schema smoke roadmap');
    INSERT INTO roadmap_nodes (id, roadmap_id, skill_name, sequence_order) VALUES (other_node_id, other_roadmap_id, 'SQL', 1);
    INSERT INTO payment_webhook_events (provider, event_id, payment_order_id) VALUES ('SCHEMA_TEST', order_id::text, order_id);

    PERFORM pg_temp.expect_constraint('INSERT INTO users (email, full_name) VALUES (''missing-password@example.invalid'', ''Invalid'')', '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO job_matching_runs (basis) VALUES (''CAREER_PROFILE'')'), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO job_matching_runs (basis, user_id, guest_session_id) VALUES (''CAREER_PROFILE'', %L, %L)', candidate_id, guest_id), '23514');
    PERFORM pg_temp.expect_constraint('INSERT INTO resumes (title, source) VALUES (''No owner'', ''MANUAL'')', '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO resumes (user_id, title, source, status, is_primary) VALUES (%L, ''Another primary'', ''MANUAL'', ''READY'', true)', candidate_id), '23505');
    PERFORM pg_temp.expect_constraint(format('UPDATE resumes SET file_size_bytes = -1 WHERE id = %L', resume_id), '23514');
    PERFORM pg_temp.expect_constraint(format('UPDATE guest_sessions SET token_used = 101 WHERE id = %L', guest_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO user_subscriptions (user_id, tier_id) VALUES (%L, %L)', candidate_id, free_tier), '23505');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO job_match_results (matching_run_id, market_job_id, final_score) VALUES (%L, %L, 1.5)', run_id, job_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO roadmap_node_prerequisites (node_id, prerequisite_node_id) VALUES (%L, %L)', node_id, node_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO assessment_answers (session_id, question_id) VALUES (%L, %L)', session_two, question_id), '23503');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO invoices (user_id, payment_order_id, invoice_number, subtotal, total_amount, status) VALUES (%L, %L, %L, 1, 2, ''ISSUED'')', candidate_id, order_id, order_id::text), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO payment_webhook_events (provider, event_id, payment_order_id) VALUES (''SCHEMA_TEST'', %L, %L)', order_id::text, order_id), '23505');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO ai_usage_logs (user_id, feature_code, input_tokens, output_tokens, total_tokens, success) VALUES (%L, ''SCHEMA_TEST'', 3, 2, 4, true)', candidate_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO subscription_quotas (tier_id, feature_code, quota_value) VALUES (%L, ''SCHEMA_NEGATIVE_QUOTA'', -1)', free_tier), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO payment_orders (user_id, tier_id, duration_months, amount, payment_provider) VALUES (%L, %L, 1, 0, ''SCHEMA_TEST'')', gen_random_uuid(), free_tier), '23503');
    PERFORM pg_temp.expect_constraint('INSERT INTO career_assistant_conversations (title) VALUES (''No principal'')', '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO ai_token_reservations (user_id, feature_code, reserved_tokens) VALUES (%L, ''SCHEMA_TEST'', -1)', candidate_id), '23514');
    PERFORM pg_temp.expect_constraint(format('UPDATE resumes SET status=''DRAFT'' WHERE id=%L', resume_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO ai_token_reservations (user_id, feature_code, reserved_tokens, consumed_tokens) VALUES (%L, ''SCHEMA_TEST'', 1, 2)', candidate_id), '23514');
    PERFORM pg_temp.expect_constraint(format('INSERT INTO roadmap_node_prerequisites (node_id, prerequisite_node_id) VALUES (%L, %L)', node_id, other_node_id), '23514');
    RAISE NOTICE 'Schema smoke passed: 22 invalid workflow operations rejected; valid fixtures accepted.';
END;
$$;

ROLLBACK;
