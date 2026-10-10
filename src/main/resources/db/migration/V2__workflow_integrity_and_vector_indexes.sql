-- Workflow guarantees derived from the core flows.
ALTER TABLE users ADD CONSTRAINT users_auth_identity_check CHECK (
    (auth_provider = 'EMAIL' AND password_hash IS NOT NULL AND length(password_hash) > 0)
    OR (auth_provider = 'GOOGLE' AND google_sub IS NOT NULL AND length(google_sub) > 0)
);
CREATE UNIQUE INDEX uq_users_email_case_insensitive ON users (lower(email));

ALTER TABLE guest_sessions ADD CONSTRAINT guest_sessions_token_budget_check
    CHECK (token_used <= token_limit);
ALTER TABLE candidate_profiles ADD CONSTRAINT candidate_profiles_salary_range_check
    CHECK (expected_salary_min IS NULL OR expected_salary_max IS NULL OR expected_salary_min <= expected_salary_max);
ALTER TABLE salary_estimations ADD CONSTRAINT salary_estimations_range_check
    CHECK (estimated_min IS NULL OR estimated_max IS NULL OR estimated_min <= estimated_max);
ALTER TABLE resumes ADD CONSTRAINT resumes_owner_check CHECK (
    (is_demo AND user_id IS NULL AND NOT is_primary) OR (NOT is_demo AND user_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_resumes_primary_per_user ON resumes (user_id) WHERE is_primary;
ALTER TABLE resumes ADD CONSTRAINT resumes_primary_ready_check
    CHECK (NOT is_primary OR status = 'READY');
CREATE INDEX ix_resumes_owner_created ON resumes (user_id, created_at DESC);

ALTER TABLE resume_experiences ADD CONSTRAINT resume_experiences_dates_check
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date);
ALTER TABLE resume_educations ADD CONSTRAINT resume_educations_dates_check
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date);
ALTER TABLE resume_projects ADD CONSTRAINT resume_projects_dates_check
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date);
ALTER TABLE resume_certifications ADD CONSTRAINT resume_certifications_dates_check
    CHECK (expiry_date IS NULL OR issue_date IS NULL OR expiry_date >= issue_date);

ALTER TABLE skill_prerequisites ADD CONSTRAINT skill_prerequisites_not_self_check
    CHECK (skill_id <> prerequisite_id);
ALTER TABLE roadmap_node_prerequisites ADD CONSTRAINT roadmap_node_prerequisites_not_self_check
    CHECK (node_id <> prerequisite_node_id);

CREATE FUNCTION edunexus_check_roadmap_prerequisite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM roadmap_nodes n JOIN roadmap_nodes p ON p.id = NEW.prerequisite_node_id
        WHERE n.id = NEW.node_id AND n.roadmap_id <> p.roadmap_id
    ) THEN
        RAISE EXCEPTION 'Prerequisite nodes must belong to the same roadmap' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER check_roadmap_prerequisite BEFORE INSERT OR UPDATE ON roadmap_node_prerequisites
    FOR EACH ROW EXECUTE FUNCTION edunexus_check_roadmap_prerequisite();

CREATE FUNCTION edunexus_check_roadmap_node_move() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.roadmap_id <> OLD.roadmap_id AND EXISTS (
        SELECT 1 FROM roadmap_node_prerequisites r JOIN roadmap_nodes peer ON
            peer.id = CASE WHEN r.node_id = OLD.id THEN r.prerequisite_node_id ELSE r.node_id END
        WHERE (r.node_id = OLD.id OR r.prerequisite_node_id = OLD.id) AND peer.roadmap_id <> NEW.roadmap_id
    ) THEN
        RAISE EXCEPTION 'Moving a node must preserve prerequisite roadmap ownership' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER check_roadmap_node_move BEFORE UPDATE OF roadmap_id ON roadmap_nodes
    FOR EACH ROW EXECUTE FUNCTION edunexus_check_roadmap_node_move();

ALTER TABLE assessment_sessions ADD CONSTRAINT assessment_sessions_not_reused_self_check
    CHECK (reused_from_session_id IS NULL OR reused_from_session_id <> id);

CREATE UNIQUE INDEX uq_assessment_question_order ON assessment_questions (session_id, sequence_order);
ALTER TABLE assessment_questions ADD CONSTRAINT uq_assessment_question_session UNIQUE (id, session_id);
ALTER TABLE assessment_answers ADD CONSTRAINT fk_assessment_answers_question_session
    FOREIGN KEY (question_id, session_id) REFERENCES assessment_questions (id, session_id);
CREATE UNIQUE INDEX uq_roadmap_node_order ON roadmap_nodes (roadmap_id, sequence_order);

ALTER TABLE user_subscriptions ADD CONSTRAINT user_subscriptions_dates_check
    CHECK (expires_at IS NULL OR expires_at > started_at);
CREATE UNIQUE INDEX uq_user_subscriptions_active ON user_subscriptions (user_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_user_subscriptions_expiry ON user_subscriptions (expires_at) WHERE status = 'ACTIVE';
ALTER TABLE consultation_threads ADD CONSTRAINT consultation_threads_distinct_users_check
    CHECK (consultant_user_id IS NULL OR consultant_user_id <> candidate_user_id);
ALTER TABLE ai_usage_logs ADD CONSTRAINT ai_usage_logs_principal_check
    CHECK (user_id IS NULL OR guest_session_id IS NULL);
ALTER TABLE ai_usage_logs ADD CONSTRAINT ai_usage_logs_token_total_check
    CHECK (total_tokens = input_tokens + output_tokens);
CREATE INDEX ix_ai_usage_user_feature_time ON ai_usage_logs (user_id, feature_code, created_at DESC);
CREATE INDEX ix_ai_usage_guest_feature_time ON ai_usage_logs (guest_session_id, feature_code, created_at DESC);
CREATE INDEX ix_payment_orders_user_status ON payment_orders (user_id, status, created_at DESC);
CREATE UNIQUE INDEX uq_invoices_payment_order ON invoices (payment_order_id);
ALTER TABLE invoices ADD CONSTRAINT invoices_total_check
    CHECK (total_amount = subtotal + tax_amount);

CREATE INDEX ix_assistant_messages_chronological ON career_assistant_messages (conversation_id, created_at, id);
CREATE INDEX ix_consultation_messages_chronological ON consultation_messages (thread_id, sent_at, id);
CREATE INDEX ix_market_jobs_active_collected ON market_job_posts (collected_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX ix_notifications_unread ON notifications (user_id, created_at DESC) WHERE NOT is_read;
CREATE INDEX ix_vector_indexing_pending ON vector_indexing_jobs (created_at) WHERE status = 'PENDING';
CREATE INDEX ix_system_logs_created ON system_logs (created_at DESC);
CREATE INDEX ix_audit_logs_created ON audit_logs (created_at DESC);

-- Provider and model are part of identity: different embedding spaces never share a current row.
CREATE UNIQUE INDEX uq_career_profile_embeddings_current ON career_profile_embeddings
    (user_id, provider, model) WHERE is_current;
CREATE UNIQUE INDEX uq_resume_embeddings_current ON resume_embeddings
    (resume_id, provider, model) WHERE is_current;
CREATE UNIQUE INDEX uq_market_job_embeddings_current ON market_job_embeddings
    (market_job_id, provider, model) WHERE is_current;
CREATE INDEX ix_career_profile_embeddings_cosine ON career_profile_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_resume_embeddings_cosine ON resume_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_market_job_embeddings_cosine ON market_job_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_rag_chunk_embeddings_cosine ON rag_chunk_embeddings
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_rag_embeddings_provider_model ON rag_chunk_embeddings (provider, model);

-- Keep updated_at accurate even for asynchronous/background updates.
CREATE FUNCTION edunexus_touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;
DO $$
DECLARE target_table text;
BEGIN
    FOR target_table IN
        SELECT table_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND column_name = 'updated_at'
    LOOP
        EXECUTE format('CREATE TRIGGER touch_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION edunexus_touch_updated_at()', target_table);
    END LOOP;
END;
$$;
