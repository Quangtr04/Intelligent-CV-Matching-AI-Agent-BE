-- EduNexus PostgreSQL schema: faithful DBML columns, primary/unique keys and references.
-- DBML enum types are stored as varchar(32) with CHECK constraints for JDBC compatibility.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255),
    auth_provider varchar(32) NOT NULL DEFAULT 'EMAIL' CHECK (auth_provider IN ('EMAIL', 'GOOGLE')),
    google_sub varchar(255) UNIQUE,
    full_name varchar(255) NOT NULL,
    avatar_url text,
    role varchar(32) NOT NULL DEFAULT 'CANDIDATE' CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN')),
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    preferred_language varchar(5) DEFAULT 'vi',
    github_url varchar(255),
    email_verified_at timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE email_verification_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    code_hash varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    verified_at timestamptz,
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    token_hash varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE candidate_profiles (
    user_id uuid PRIMARY KEY,
    current_job_title varchar(255),
    current_company varchar(255),
    target_job_title varchar(255),
    career_goal text,
    years_experience decimal(4,1) CHECK (years_experience >= 0),
    expected_salary_min decimal(14,2) CHECK (expected_salary_min >= 0),
    expected_salary_max decimal(14,2) CHECK (expected_salary_max >= 0),
    salary_currency varchar(10) DEFAULT 'VND',
    preferred_location varchar(255),
    workplace_preference varchar(50),
    employment_type varchar(50),
    profile_summary text,
    preferences_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE guest_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_key varchar(120) NOT NULL UNIQUE,
    token_limit int NOT NULL DEFAULT 0 CHECK (token_limit >= 0),
    token_used int NOT NULL DEFAULT 0 CHECK (token_used >= 0),
    converted_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz
);

CREATE TABLE trial_usage_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_session_id uuid NOT NULL,
    feature_code varchar(100) NOT NULL,
    demo_resume_id uuid,
    tokens_used int NOT NULL DEFAULT 0 CHECK (tokens_used >= 0),
    status varchar(32) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(150) NOT NULL,
    slug varchar(150) NOT NULL UNIQUE,
    category varchar(50),
    major varchar(50),
    description text,
    difficulty_level smallint DEFAULT 1 CHECK (difficulty_level > 0),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE skill_prerequisites (
    skill_id uuid NOT NULL,
    prerequisite_id uuid NOT NULL,
    PRIMARY KEY (skill_id, prerequisite_id)
);

CREATE TABLE candidate_profile_skills (
    user_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    proficiency_level varchar(50),
    years_experience decimal(4,1) CHECK (years_experience >= 0),
    PRIMARY KEY (user_id, skill_id)
);

CREATE TABLE career_profile_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    provider varchar(30) NOT NULL,
    model varchar(80) NOT NULL,
    embedding vector(1536),
    content_hash varchar(64),
    is_current boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(150) NOT NULL,
    career_path varchar(150),
    language varchar(20) DEFAULT 'EN',
    is_trial_enabled boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resumes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid,
    template_id uuid,
    title varchar(255) NOT NULL,
    source varchar(32) NOT NULL CHECK (source IN ('UPLOAD', 'AI_BUILDER', 'MANUAL')),
    status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PARSING', 'REVIEW_REQUIRED', 'READY', 'INVALID', 'ARCHIVED')),
    is_demo boolean NOT NULL DEFAULT false,
    is_primary boolean NOT NULL DEFAULT false,
    language varchar(20),
    file_name varchar(255),
    file_url text,
    mime_type varchar(100),
    file_size_bytes bigint CHECK (file_size_bytes >= 0),
    raw_content text,
    structured_data jsonb,
    content_hash varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_parse_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    parser_name varchar(100),
    parser_version varchar(50),
    ocr_used boolean NOT NULL DEFAULT false,
    raw_text text,
    extracted_data jsonb,
    normalized_data jsonb,
    confidence_score decimal(5,2) CHECK (confidence_score BETWEEN 0 AND 100),
    status varchar(32) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_experiences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    company_name varchar(255),
    job_title varchar(255),
    start_date date,
    end_date date,
    is_current boolean NOT NULL DEFAULT false,
    description text
);

CREATE TABLE resume_educations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    school_name varchar(255),
    degree varchar(255),
    major varchar(255),
    start_date date,
    end_date date,
    description text
);

CREATE TABLE resume_projects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    project_name varchar(255),
    role varchar(255),
    description text,
    technologies jsonb,
    project_url text,
    start_date date,
    end_date date
);

CREATE TABLE resume_certifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    issuer varchar(255),
    issue_date date,
    expiry_date date,
    credential_url text
);

CREATE TABLE resume_skills (
    resume_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    proficiency_level varchar(50),
    years_experience decimal(4,1) CHECK (years_experience >= 0),
    source varchar(50),
    PRIMARY KEY (resume_id, skill_id)
);

CREATE TABLE resume_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    provider varchar(30) NOT NULL,
    model varchar(80) NOT NULL,
    embedding vector(1536),
    content_hash varchar(64),
    is_current boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_analyses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL,
    analysis_type varchar(50) NOT NULL,
    overall_score decimal(5,2) CHECK (overall_score BETWEEN 0 AND 100),
    ats_score decimal(5,2) CHECK (ats_score BETWEEN 0 AND 100),
    summary text,
    strengths_json jsonb,
    issues_json jsonb,
    model_name varchar(150),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE resume_analysis_suggestions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id uuid NOT NULL,
    category varchar(100),
    severity varchar(30),
    original_content text,
    suggested_content text,
    explanation text,
    accepted boolean,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE assessment_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    target_job_title varchar(255),
    market_job_id uuid,
    job_role_category_snapshot varchar(100),
    part1_count smallint CHECK (part1_count >= 0),
    part2_count smallint CHECK (part2_count >= 0),
    skill_scores jsonb,
    status varchar(20) NOT NULL DEFAULT 'in_progress',
    is_current boolean NOT NULL DEFAULT true,
    reused_from_session_id uuid,
    started_at timestamptz NOT NULL DEFAULT now(),
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE assessment_questions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    sequence_order smallint NOT NULL CHECK (sequence_order >= 0),
    part smallint NOT NULL CHECK (part IN (1, 2)),
    question_text text NOT NULL,
    options jsonb NOT NULL,
    correct_option char(1),
    related_skill_id uuid,
    related_skill_name varchar(150),
    explanation text
);

CREATE TABLE assessment_answers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    question_id uuid NOT NULL,
    selected_option char(1),
    is_correct boolean,
    answered_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gap_analyses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    market_job_id uuid,
    resume_id uuid,
    assessment_session_id uuid,
    target_job_title varchar(255),
    input_source varchar(30) NOT NULL,
    version smallint NOT NULL DEFAULT 1 CHECK (version > 0),
    is_latest boolean NOT NULL DEFAULT true,
    summary jsonb,
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE gap_analysis_skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    gap_analysis_id uuid NOT NULL,
    skill_id uuid,
    skill_name varchar(150) NOT NULL,
    gap_status varchar(20) NOT NULL,
    current_level varchar(20),
    target_level varchar(20),
    urgency_score smallint,
    reasoning text,
    is_required_for_target boolean NOT NULL DEFAULT true
);

CREATE TABLE roadmaps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    market_job_id uuid,
    gap_analysis_id uuid,
    target_job_title varchar(255),
    title varchar(255) NOT NULL,
    estimated_total_hours int CHECK (estimated_total_hours >= 0),
    status varchar(20) NOT NULL DEFAULT 'generating',
    is_outdated boolean NOT NULL DEFAULT false,
    progress_percent smallint NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE roadmap_nodes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    roadmap_id uuid NOT NULL,
    skill_id uuid,
    skill_name varchar(150) NOT NULL,
    description text,
    sequence_order smallint NOT NULL CHECK (sequence_order >= 0),
    estimated_hours int CHECK (estimated_hours >= 0),
    is_prerequisite boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'not_started',
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE roadmap_node_prerequisites (
    node_id uuid NOT NULL,
    prerequisite_node_id uuid NOT NULL,
    PRIMARY KEY (node_id, prerequisite_node_id)
);

CREATE TABLE learning_resources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(255) NOT NULL,
    resource_type varchar(30) NOT NULL,
    provider varchar(150),
    url text NOT NULL UNIQUE,
    description text,
    language varchar(10) DEFAULT 'vi',
    duration_minutes int CHECK (duration_minutes >= 0),
    is_free boolean NOT NULL DEFAULT true,
    source_type varchar(30) NOT NULL DEFAULT 'WEB',
    metadata_json jsonb,
    is_active boolean NOT NULL DEFAULT true,
    discovered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE roadmap_node_resources (
    roadmap_node_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    sequence_order smallint CHECK (sequence_order >= 0),
    relevance_score decimal(5,2) CHECK (relevance_score BETWEEN 0 AND 100),
    recommendation_reason text,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (roadmap_node_id, resource_id)
);

CREATE TABLE market_crawl_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_site varchar(50) NOT NULL,
    keyword varchar(255) NOT NULL,
    role_category varchar(100) NOT NULL,
    requested_location varchar(120),
    requested_limit int NOT NULL CHECK (requested_limit > 0),
    status varchar(30) NOT NULL,
    message text,
    fetched_urls int NOT NULL DEFAULT 0 CHECK (fetched_urls >= 0),
    parsed_jobs int NOT NULL DEFAULT 0 CHECK (parsed_jobs >= 0),
    imported_jobs int NOT NULL DEFAULT 0 CHECK (imported_jobs >= 0),
    imported_skills int NOT NULL DEFAULT 0 CHECK (imported_skills >= 0),
    skipped_duplicates int NOT NULL DEFAULT 0 CHECK (skipped_duplicates >= 0),
    search_pages_checked int NOT NULL DEFAULT 0 CHECK (search_pages_checked >= 0),
    candidate_urls_discovered int NOT NULL DEFAULT 0 CHECK (candidate_urls_discovered >= 0),
    detail_pages_checked int NOT NULL DEFAULT 0 CHECK (detail_pages_checked >= 0),
    wrong_region_pages int NOT NULL DEFAULT 0 CHECK (wrong_region_pages >= 0),
    rejected_pages int NOT NULL DEFAULT 0 CHECK (rejected_pages >= 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE market_crawl_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    crawl_run_id uuid NOT NULL,
    source_url text,
    item_status varchar(30) NOT NULL,
    http_status int,
    job_title varchar(255),
    raw_content_hash varchar(64),
    message text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE market_job_posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_site varchar(50) NOT NULL,
    source_url text,
    raw_content_hash varchar(64) NOT NULL UNIQUE,
    job_title varchar(255) NOT NULL,
    company_name varchar(255),
    location varchar(255),
    salary_text varchar(255),
    role_category varchar(100) NOT NULL,
    raw_content text NOT NULL,
    original_content text,
    original_content_format varchar(32) NOT NULL DEFAULT 'plain_text',
    original_content_quality varchar(32) NOT NULL DEFAULT 'unknown',
    original_content_extracted_at timestamptz,
    parsed_description text,
    parsed_requirements text,
    parsed_responsibilities text,
    parsed_benefits text,
    parsed_company_overview text,
    parsed_seniority varchar(64),
    parsed_confidence decimal(5,2) CHECK (parsed_confidence BETWEEN 0 AND 100),
    parsed_at timestamptz,
    posted_at timestamptz,
    collected_at timestamptz NOT NULL DEFAULT now(),
    status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'REMOVED', 'INVALID'))
);

CREATE TABLE market_job_skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    market_job_id uuid NOT NULL,
    skill_id uuid,
    skill_name varchar(100) NOT NULL,
    normalized_skill varchar(100) NOT NULL,
    is_required boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE market_job_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    market_job_id uuid NOT NULL,
    provider varchar(30) NOT NULL,
    model varchar(80) NOT NULL,
    embedding vector(1536),
    content_hash varchar(64),
    is_current boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_matching_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid,
    guest_session_id uuid,
    basis varchar(32) NOT NULL CHECK (basis IN ('CAREER_PROFILE', 'ALL_CVS', 'SELECTED_CV')),
    profile_snapshot jsonb,
    provider varchar(30),
    model varchar(80),
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT job_matching_runs_principal_check CHECK ((user_id IS NOT NULL) <> (guest_session_id IS NOT NULL))
);

CREATE TABLE matching_run_resumes (
    matching_run_id uuid NOT NULL,
    resume_id uuid NOT NULL,
    PRIMARY KEY (matching_run_id, resume_id)
);

CREATE TABLE job_match_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    matching_run_id uuid NOT NULL,
    market_job_id uuid NOT NULL,
    vector_similarity_score decimal(6,5) CHECK (vector_similarity_score BETWEEN -1 AND 1),
    llm_rerank_score decimal(6,5) CHECK (llm_rerank_score BETWEEN 0 AND 1),
    final_score decimal(6,5) CHECK (final_score BETWEEN 0 AND 1),
    rank_position int,
    match_reason_json jsonb,
    strengths_json jsonb,
    skill_gaps_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_resume_analyses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    resume_id uuid NOT NULL,
    market_job_id uuid NOT NULL,
    match_result_id uuid,
    similarity_score decimal(6,5) CHECK (similarity_score BETWEEN -1 AND 1),
    ats_score decimal(5,2) CHECK (ats_score BETWEEN 0 AND 100),
    strengths_json jsonb,
    missing_skills_json jsonb,
    improvement_suggestions_json jsonb,
    model_name varchar(150),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE application_drafts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    market_job_id uuid NOT NULL,
    resume_id uuid,
    draft_type varchar(30) NOT NULL,
    content text NOT NULL,
    model_used varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE external_apply_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    market_job_id uuid NOT NULL,
    resume_id uuid,
    event_type varchar(30) NOT NULL,
    target_url text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE career_assistant_conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid,
    guest_session_id uuid,
    title varchar(255),
    status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'CLOSED')),
    context_type varchar(30) NOT NULL DEFAULT 'general',
    related_job_id uuid,
    related_resume_id uuid,
    related_gap_analysis_id uuid,
    related_roadmap_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_message_at timestamptz,
    CONSTRAINT career_assistant_conversations_principal_check CHECK ((user_id IS NOT NULL) <> (guest_session_id IS NOT NULL))
);

CREATE TABLE career_assistant_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL,
    sender_type varchar(32) NOT NULL CHECK (sender_type IN ('USER', 'ASSISTANT', 'SYSTEM')),
    message_text text NOT NULL,
    message_format varchar(20) NOT NULL DEFAULT 'plain_text',
    input_tokens int CHECK (input_tokens >= 0),
    output_tokens int CHECK (output_tokens >= 0),
    model_used varchar(80),
    retrieval_used boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE career_assistant_message_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    rag_chunk_id uuid,
    rag_document_id uuid,
    source_label varchar(255),
    similarity_score decimal(6,4) CHECK (similarity_score BETWEEN -1 AND 1),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE career_assistant_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    rating smallint CHECK (rating BETWEEN 1 AND 5),
    is_helpful boolean,
    feedback_text text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consultation_threads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_user_id uuid NOT NULL,
    consultant_user_id uuid,
    status varchar(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    subject varchar(255),
    request_type varchar(50),
    request_description text,
    priority varchar(20)
);

CREATE TABLE consultation_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id uuid NOT NULL,
    sender_user_id uuid NOT NULL,
    message_type varchar(30) NOT NULL DEFAULT 'TEXT',
    content text NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz
);

CREATE TABLE subscription_tiers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tier_code varchar(30) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    price_monthly decimal(10,2) NOT NULL CHECK (price_monthly >= 0),
    currency char(3) NOT NULL DEFAULT 'VND',
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscription_quotas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tier_id uuid NOT NULL,
    feature_code varchar(100) NOT NULL,
    quota_value bigint NOT NULL CHECK (quota_value >= 0),
    quota_period varchar(30)
);

CREATE TABLE user_subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    tier_id uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    started_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    auto_renew boolean NOT NULL DEFAULT false,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    subscription_id uuid,
    tier_id uuid NOT NULL,
    duration_months smallint NOT NULL CHECK (duration_months > 0),
    amount decimal(10,2) NOT NULL CHECK (amount >= 0),
    currency char(3) NOT NULL DEFAULT 'VND',
    payment_provider varchar(20) NOT NULL,
    provider_order_id varchar(255) UNIQUE,
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED')),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id uuid NOT NULL,
    user_id uuid NOT NULL,
    assigned_admin_id uuid,
    subject varchar(255),
    description text,
    status varchar(30) NOT NULL DEFAULT 'OPEN',
    resolution text,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE TABLE subscription_renewal_notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    notification_type varchar(20) NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    type varchar(100),
    title varchar(255),
    content text,
    reference_type varchar(100),
    reference_id uuid,
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz
);

CREATE TABLE rag_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uploaded_by uuid,
    title varchar(255) NOT NULL,
    source_type varchar(50) NOT NULL,
    file_url text,
    related_skill_ids uuid[],
    metadata jsonb,
    chunks_count int NOT NULL DEFAULT 0 CHECK (chunks_count >= 0),
    embedding_status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (embedding_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rag_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL,
    chunk_index int NOT NULL CHECK (chunk_index >= 0),
    content text NOT NULL,
    token_count int CHECK (token_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rag_chunk_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id uuid NOT NULL,
    provider varchar(30) NOT NULL,
    model varchar(80) NOT NULL,
    embedding vector(1536) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rag_query_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid,
    query_type varchar(50) NOT NULL,
    entity_id uuid,
    prompt_tokens int NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens int NOT NULL CHECK (completion_tokens >= 0),
    cost_usd decimal(10,6) NOT NULL CHECK (cost_usd >= 0),
    duration_ms int NOT NULL CHECK (duration_ms >= 0),
    model_used varchar(50) NOT NULL,
    success boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_configurations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    config_type varchar(50) NOT NULL,
    provider varchar(100),
    model_name varchar(150),
    config_json jsonb,
    version int NOT NULL DEFAULT 1 CHECK (version > 0),
    is_active boolean NOT NULL DEFAULT true,
    updated_by_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_prompt_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_code varchar(100) NOT NULL,
    name varchar(150),
    system_prompt text,
    user_prompt_template text,
    version int NOT NULL DEFAULT 1 CHECK (version > 0),
    is_active boolean NOT NULL DEFAULT true,
    updated_by_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_usage_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid,
    guest_session_id uuid,
    subscription_id uuid,
    feature_code varchar(100) NOT NULL,
    provider varchar(100),
    model_name varchar(150),
    input_tokens int NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens int NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens int NOT NULL DEFAULT 0 CHECK (total_tokens >= 0),
    estimated_cost decimal(14,6) CHECK (estimated_cost >= 0),
    latency_ms bigint CHECK (latency_ms >= 0),
    success boolean NOT NULL,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vector_store_metrics (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(100),
    index_name varchar(150),
    vector_count bigint CHECK (vector_count >= 0),
    storage_size_bytes bigint CHECK (storage_size_bytes >= 0),
    response_time_ms bigint CHECK (response_time_ms >= 0),
    status varchar(50),
    measured_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE announcements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by_user_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    content text NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    publish_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE announcement_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id uuid NOT NULL,
    target_type varchar(30) NOT NULL,
    target_role varchar(32) CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN')),
    target_user_id uuid
);

CREATE TABLE admin_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id uuid NOT NULL,
    action_type varchar(50) NOT NULL,
    target_type varchar(50),
    target_id uuid,
    metadata jsonb,
    ip_address inet,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE system_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    level varchar(30),
    component varchar(100),
    event_type varchar(100),
    message text,
    context_json jsonb,
    user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE support_tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    assigned_admin_id uuid,
    subject varchar(255) NOT NULL,
    category varchar(50),
    description text NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority varchar(20) DEFAULT 'NORMAL',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE TABLE support_ticket_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL,
    sender_user_id uuid NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_match_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    match_result_id uuid NOT NULL,
    feedback_type varchar(20),
    rating smallint CHECK (rating BETWEEN 1 AND 5),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    payment_order_id uuid NOT NULL,
    invoice_number varchar(100) NOT NULL UNIQUE,
    subtotal decimal(14,2) NOT NULL CHECK (subtotal >= 0),
    tax_amount decimal(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    total_amount decimal(14,2) NOT NULL CHECK (total_amount >= 0),
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(30) NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    invoice_url text
);

CREATE TABLE vector_indexing_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type varchar(30) NOT NULL,
    entity_id uuid,
    operation varchar(30) NOT NULL,
    provider varchar(50),
    model varchar(100),
    status varchar(32) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_message text,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid,
    action varchar(100) NOT NULL,
    entity_type varchar(100),
    entity_id uuid,
    old_value jsonb,
    new_value jsonb,
    ip_address inet,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE salary_estimations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    market_job_id uuid,
    target_job_title varchar(255),
    estimated_min decimal(14,2) CHECK (estimated_min >= 0),
    estimated_max decimal(14,2) CHECK (estimated_max >= 0),
    currency varchar(10),
    reasoning jsonb,
    model_name varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Add all references after table creation so forward and self-references are valid.
ALTER TABLE roadmap_node_resources ADD CONSTRAINT fk_roadmap_node_resources_roadmap_node_id FOREIGN KEY (roadmap_node_id) REFERENCES roadmap_nodes (id);
ALTER TABLE roadmap_node_resources ADD CONSTRAINT fk_roadmap_node_resources_resource_id FOREIGN KEY (resource_id) REFERENCES learning_resources (id);
ALTER TABLE email_verification_codes ADD CONSTRAINT fk_email_verification_codes_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE gap_analyses ADD CONSTRAINT fk_gap_analyses_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE gap_analyses ADD CONSTRAINT fk_gap_analyses_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE gap_analyses ADD CONSTRAINT fk_gap_analyses_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE gap_analyses ADD CONSTRAINT fk_gap_analyses_assessment_session_id FOREIGN KEY (assessment_session_id) REFERENCES assessment_sessions (id);
ALTER TABLE assessment_sessions ADD CONSTRAINT fk_assessment_sessions_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES users (id);
ALTER TABLE salary_estimations ADD CONSTRAINT fk_salary_estimations_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE salary_estimations ADD CONSTRAINT fk_salary_estimations_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_payment_order_id FOREIGN KEY (payment_order_id) REFERENCES payment_orders (id);
ALTER TABLE job_matching_runs ADD CONSTRAINT fk_job_matching_runs_guest_session_id FOREIGN KEY (guest_session_id) REFERENCES guest_sessions (id);
ALTER TABLE job_match_feedback ADD CONSTRAINT fk_job_match_feedback_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE job_match_feedback ADD CONSTRAINT fk_job_match_feedback_match_result_id FOREIGN KEY (match_result_id) REFERENCES job_match_results (id);
ALTER TABLE subscription_quotas ADD CONSTRAINT fk_subscription_quotas_tier_id FOREIGN KEY (tier_id) REFERENCES subscription_tiers (id);
ALTER TABLE support_tickets ADD CONSTRAINT fk_support_tickets_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE support_tickets ADD CONSTRAINT fk_support_tickets_assigned_admin_id FOREIGN KEY (assigned_admin_id) REFERENCES users (id);
ALTER TABLE support_ticket_messages ADD CONSTRAINT fk_support_ticket_messages_ticket_id FOREIGN KEY (ticket_id) REFERENCES support_tickets (id);
ALTER TABLE support_ticket_messages ADD CONSTRAINT fk_support_ticket_messages_sender_user_id FOREIGN KEY (sender_user_id) REFERENCES users (id);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE candidate_profiles ADD CONSTRAINT fk_candidate_profiles_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE guest_sessions ADD CONSTRAINT fk_guest_sessions_converted_user_id FOREIGN KEY (converted_user_id) REFERENCES users (id);
ALTER TABLE trial_usage_events ADD CONSTRAINT fk_trial_usage_events_guest_session_id FOREIGN KEY (guest_session_id) REFERENCES guest_sessions (id);
ALTER TABLE trial_usage_events ADD CONSTRAINT fk_trial_usage_events_demo_resume_id FOREIGN KEY (demo_resume_id) REFERENCES resumes (id);
ALTER TABLE skill_prerequisites ADD CONSTRAINT fk_skill_prerequisites_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE skill_prerequisites ADD CONSTRAINT fk_skill_prerequisites_prerequisite_id FOREIGN KEY (prerequisite_id) REFERENCES skills (id);
ALTER TABLE candidate_profile_skills ADD CONSTRAINT fk_candidate_profile_skills_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE candidate_profile_skills ADD CONSTRAINT fk_candidate_profile_skills_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE career_profile_embeddings ADD CONSTRAINT fk_career_profile_embeddings_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE resumes ADD CONSTRAINT fk_resumes_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE resumes ADD CONSTRAINT fk_resumes_template_id FOREIGN KEY (template_id) REFERENCES resume_templates (id);
ALTER TABLE resume_parse_results ADD CONSTRAINT fk_resume_parse_results_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_experiences ADD CONSTRAINT fk_resume_experiences_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_educations ADD CONSTRAINT fk_resume_educations_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_projects ADD CONSTRAINT fk_resume_projects_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_certifications ADD CONSTRAINT fk_resume_certifications_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_skills ADD CONSTRAINT fk_resume_skills_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_skills ADD CONSTRAINT fk_resume_skills_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE resume_embeddings ADD CONSTRAINT fk_resume_embeddings_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_analyses ADD CONSTRAINT fk_resume_analyses_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE resume_analysis_suggestions ADD CONSTRAINT fk_resume_analysis_suggestions_analysis_id FOREIGN KEY (analysis_id) REFERENCES resume_analyses (id);
ALTER TABLE assessment_sessions ADD CONSTRAINT fk_assessment_sessions_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE assessment_sessions ADD CONSTRAINT fk_assessment_sessions_reused_from_session_id FOREIGN KEY (reused_from_session_id) REFERENCES assessment_sessions (id);
ALTER TABLE assessment_questions ADD CONSTRAINT fk_assessment_questions_session_id FOREIGN KEY (session_id) REFERENCES assessment_sessions (id);
ALTER TABLE assessment_questions ADD CONSTRAINT fk_assessment_questions_related_skill_id FOREIGN KEY (related_skill_id) REFERENCES skills (id);
ALTER TABLE assessment_answers ADD CONSTRAINT fk_assessment_answers_session_id FOREIGN KEY (session_id) REFERENCES assessment_sessions (id);
ALTER TABLE assessment_answers ADD CONSTRAINT fk_assessment_answers_question_id FOREIGN KEY (question_id) REFERENCES assessment_questions (id);
ALTER TABLE gap_analysis_skills ADD CONSTRAINT fk_gap_analysis_skills_gap_analysis_id FOREIGN KEY (gap_analysis_id) REFERENCES gap_analyses (id);
ALTER TABLE gap_analysis_skills ADD CONSTRAINT fk_gap_analysis_skills_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE roadmaps ADD CONSTRAINT fk_roadmaps_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE roadmaps ADD CONSTRAINT fk_roadmaps_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE roadmaps ADD CONSTRAINT fk_roadmaps_gap_analysis_id FOREIGN KEY (gap_analysis_id) REFERENCES gap_analyses (id);
ALTER TABLE roadmap_nodes ADD CONSTRAINT fk_roadmap_nodes_roadmap_id FOREIGN KEY (roadmap_id) REFERENCES roadmaps (id);
ALTER TABLE roadmap_nodes ADD CONSTRAINT fk_roadmap_nodes_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE roadmap_node_prerequisites ADD CONSTRAINT fk_roadmap_node_prerequisites_node_id FOREIGN KEY (node_id) REFERENCES roadmap_nodes (id);
ALTER TABLE roadmap_node_prerequisites ADD CONSTRAINT fk_roadmap_node_prerequisites_prerequisite_node_id FOREIGN KEY (prerequisite_node_id) REFERENCES roadmap_nodes (id);
ALTER TABLE market_crawl_items ADD CONSTRAINT fk_market_crawl_items_crawl_run_id FOREIGN KEY (crawl_run_id) REFERENCES market_crawl_runs (id);
ALTER TABLE market_job_skills ADD CONSTRAINT fk_market_job_skills_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE market_job_skills ADD CONSTRAINT fk_market_job_skills_skill_id FOREIGN KEY (skill_id) REFERENCES skills (id);
ALTER TABLE market_job_embeddings ADD CONSTRAINT fk_market_job_embeddings_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE job_matching_runs ADD CONSTRAINT fk_job_matching_runs_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE matching_run_resumes ADD CONSTRAINT fk_matching_run_resumes_matching_run_id FOREIGN KEY (matching_run_id) REFERENCES job_matching_runs (id);
ALTER TABLE matching_run_resumes ADD CONSTRAINT fk_matching_run_resumes_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE job_match_results ADD CONSTRAINT fk_job_match_results_matching_run_id FOREIGN KEY (matching_run_id) REFERENCES job_matching_runs (id);
ALTER TABLE job_match_results ADD CONSTRAINT fk_job_match_results_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE job_resume_analyses ADD CONSTRAINT fk_job_resume_analyses_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE job_resume_analyses ADD CONSTRAINT fk_job_resume_analyses_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE job_resume_analyses ADD CONSTRAINT fk_job_resume_analyses_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE job_resume_analyses ADD CONSTRAINT fk_job_resume_analyses_match_result_id FOREIGN KEY (match_result_id) REFERENCES job_match_results (id);
ALTER TABLE application_drafts ADD CONSTRAINT fk_application_drafts_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE application_drafts ADD CONSTRAINT fk_application_drafts_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE application_drafts ADD CONSTRAINT fk_application_drafts_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE external_apply_events ADD CONSTRAINT fk_external_apply_events_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE external_apply_events ADD CONSTRAINT fk_external_apply_events_market_job_id FOREIGN KEY (market_job_id) REFERENCES market_job_posts (id);
ALTER TABLE external_apply_events ADD CONSTRAINT fk_external_apply_events_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_guest_session_id FOREIGN KEY (guest_session_id) REFERENCES guest_sessions (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_related_job_id FOREIGN KEY (related_job_id) REFERENCES market_job_posts (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_related_resume_id FOREIGN KEY (related_resume_id) REFERENCES resumes (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_related_gap_analysis_id FOREIGN KEY (related_gap_analysis_id) REFERENCES gap_analyses (id);
ALTER TABLE career_assistant_conversations ADD CONSTRAINT fk_career_assistant_conversations_related_roadmap_id FOREIGN KEY (related_roadmap_id) REFERENCES roadmaps (id);
ALTER TABLE career_assistant_messages ADD CONSTRAINT fk_career_assistant_messages_conversation_id FOREIGN KEY (conversation_id) REFERENCES career_assistant_conversations (id);
ALTER TABLE career_assistant_message_sources ADD CONSTRAINT fk_career_assistant_message_sources_message_id FOREIGN KEY (message_id) REFERENCES career_assistant_messages (id);
ALTER TABLE career_assistant_message_sources ADD CONSTRAINT fk_career_assistant_message_sources_rag_chunk_id FOREIGN KEY (rag_chunk_id) REFERENCES rag_chunks (id);
ALTER TABLE career_assistant_message_sources ADD CONSTRAINT fk_career_assistant_message_sources_rag_document_id FOREIGN KEY (rag_document_id) REFERENCES rag_documents (id);
ALTER TABLE career_assistant_feedback ADD CONSTRAINT fk_career_assistant_feedback_message_id FOREIGN KEY (message_id) REFERENCES career_assistant_messages (id);
ALTER TABLE career_assistant_feedback ADD CONSTRAINT fk_career_assistant_feedback_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE consultation_threads ADD CONSTRAINT fk_consultation_threads_candidate_user_id FOREIGN KEY (candidate_user_id) REFERENCES users (id);
ALTER TABLE consultation_threads ADD CONSTRAINT fk_consultation_threads_consultant_user_id FOREIGN KEY (consultant_user_id) REFERENCES users (id);
ALTER TABLE consultation_messages ADD CONSTRAINT fk_consultation_messages_thread_id FOREIGN KEY (thread_id) REFERENCES consultation_threads (id);
ALTER TABLE consultation_messages ADD CONSTRAINT fk_consultation_messages_sender_user_id FOREIGN KEY (sender_user_id) REFERENCES users (id);
ALTER TABLE user_subscriptions ADD CONSTRAINT fk_user_subscriptions_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE user_subscriptions ADD CONSTRAINT fk_user_subscriptions_tier_id FOREIGN KEY (tier_id) REFERENCES subscription_tiers (id);
ALTER TABLE payment_orders ADD CONSTRAINT fk_payment_orders_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE payment_orders ADD CONSTRAINT fk_payment_orders_subscription_id FOREIGN KEY (subscription_id) REFERENCES user_subscriptions (id);
ALTER TABLE payment_orders ADD CONSTRAINT fk_payment_orders_tier_id FOREIGN KEY (tier_id) REFERENCES subscription_tiers (id);
ALTER TABLE payment_reports ADD CONSTRAINT fk_payment_reports_payment_order_id FOREIGN KEY (payment_order_id) REFERENCES payment_orders (id);
ALTER TABLE payment_reports ADD CONSTRAINT fk_payment_reports_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE payment_reports ADD CONSTRAINT fk_payment_reports_assigned_admin_id FOREIGN KEY (assigned_admin_id) REFERENCES users (id);
ALTER TABLE subscription_renewal_notifications ADD CONSTRAINT fk_subscription_renewal_notifications_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE subscription_renewal_notifications ADD CONSTRAINT fk_subscription_renewal_notifications_subscription_id FOREIGN KEY (subscription_id) REFERENCES user_subscriptions (id);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE rag_documents ADD CONSTRAINT fk_rag_documents_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id);
ALTER TABLE rag_chunks ADD CONSTRAINT fk_rag_chunks_document_id FOREIGN KEY (document_id) REFERENCES rag_documents (id);
ALTER TABLE rag_chunk_embeddings ADD CONSTRAINT fk_rag_chunk_embeddings_chunk_id FOREIGN KEY (chunk_id) REFERENCES rag_chunks (id);
ALTER TABLE rag_query_logs ADD CONSTRAINT fk_rag_query_logs_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE ai_configurations ADD CONSTRAINT fk_ai_configurations_updated_by_user_id FOREIGN KEY (updated_by_user_id) REFERENCES users (id);
ALTER TABLE ai_prompt_templates ADD CONSTRAINT fk_ai_prompt_templates_updated_by_user_id FOREIGN KEY (updated_by_user_id) REFERENCES users (id);
ALTER TABLE ai_usage_logs ADD CONSTRAINT fk_ai_usage_logs_user_id FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE ai_usage_logs ADD CONSTRAINT fk_ai_usage_logs_guest_session_id FOREIGN KEY (guest_session_id) REFERENCES guest_sessions (id);
ALTER TABLE ai_usage_logs ADD CONSTRAINT fk_ai_usage_logs_subscription_id FOREIGN KEY (subscription_id) REFERENCES user_subscriptions (id);
ALTER TABLE announcements ADD CONSTRAINT fk_announcements_created_by_user_id FOREIGN KEY (created_by_user_id) REFERENCES users (id);
ALTER TABLE announcement_targets ADD CONSTRAINT fk_announcement_targets_announcement_id FOREIGN KEY (announcement_id) REFERENCES announcements (id);
ALTER TABLE announcement_targets ADD CONSTRAINT fk_announcement_targets_target_user_id FOREIGN KEY (target_user_id) REFERENCES users (id);
ALTER TABLE admin_actions ADD CONSTRAINT fk_admin_actions_admin_user_id FOREIGN KEY (admin_user_id) REFERENCES users (id);
ALTER TABLE system_logs ADD CONSTRAINT fk_system_logs_user_id FOREIGN KEY (user_id) REFERENCES users (id);

-- DBML composite indexes.
CREATE INDEX ix_refresh_tokens_user_id_revoked_at ON refresh_tokens (user_id, revoked_at);
CREATE UNIQUE INDEX uq_resume_embeddings_resume_id_provider_model_content_hash ON resume_embeddings (resume_id, provider, model, content_hash);
CREATE UNIQUE INDEX uq_assessment_answers_session_id_question_id ON assessment_answers (session_id, question_id);
CREATE UNIQUE INDEX uq_market_job_skills_market_job_id_normalized_skill ON market_job_skills (market_job_id, normalized_skill);
CREATE UNIQUE INDEX uq_market_job_embeddings_market_job_id_provider_mode_ee8869b8 ON market_job_embeddings (market_job_id, provider, model, content_hash);
CREATE UNIQUE INDEX uq_job_match_results_matching_run_id_market_job_id ON job_match_results (matching_run_id, market_job_id);
CREATE UNIQUE INDEX uq_subscription_quotas_tier_id_feature_code ON subscription_quotas (tier_id, feature_code);
CREATE UNIQUE INDEX uq_subscription_renewal_notifications_subscription_i_ecf1240f ON subscription_renewal_notifications (subscription_id, notification_type);
CREATE UNIQUE INDEX uq_rag_chunks_document_id_chunk_index ON rag_chunks (document_id, chunk_index);
CREATE UNIQUE INDEX uq_rag_chunk_embeddings_chunk_id_provider_model ON rag_chunk_embeddings (chunk_id, provider, model);
CREATE UNIQUE INDEX uq_job_match_feedback_user_id_match_result_id ON job_match_feedback (user_id, match_result_id);

-- Foreign-key lookups used by owned-resource APIs, background work and history.
CREATE INDEX ix_roadmap_node_resources_resource_id ON roadmap_node_resources (resource_id);
CREATE INDEX ix_email_verification_codes_user_id ON email_verification_codes (user_id);
CREATE INDEX ix_gap_analyses_user_id ON gap_analyses (user_id);
CREATE INDEX ix_gap_analyses_market_job_id ON gap_analyses (market_job_id);
CREATE INDEX ix_gap_analyses_resume_id ON gap_analyses (resume_id);
CREATE INDEX ix_gap_analyses_assessment_session_id ON gap_analyses (assessment_session_id);
CREATE INDEX ix_assessment_sessions_market_job_id ON assessment_sessions (market_job_id);
CREATE INDEX ix_audit_logs_actor_user_id ON audit_logs (actor_user_id);
CREATE INDEX ix_salary_estimations_user_id ON salary_estimations (user_id);
CREATE INDEX ix_salary_estimations_market_job_id ON salary_estimations (market_job_id);
CREATE INDEX ix_invoices_user_id ON invoices (user_id);
CREATE INDEX ix_invoices_payment_order_id ON invoices (payment_order_id);
CREATE INDEX ix_job_matching_runs_guest_session_id ON job_matching_runs (guest_session_id);
CREATE INDEX ix_job_match_feedback_match_result_id ON job_match_feedback (match_result_id);
CREATE INDEX ix_support_tickets_user_id ON support_tickets (user_id);
CREATE INDEX ix_support_tickets_assigned_admin_id ON support_tickets (assigned_admin_id);
CREATE INDEX ix_support_ticket_messages_ticket_id ON support_ticket_messages (ticket_id);
CREATE INDEX ix_support_ticket_messages_sender_user_id ON support_ticket_messages (sender_user_id);
CREATE INDEX ix_guest_sessions_converted_user_id ON guest_sessions (converted_user_id);
CREATE INDEX ix_trial_usage_events_guest_session_id ON trial_usage_events (guest_session_id);
CREATE INDEX ix_trial_usage_events_demo_resume_id ON trial_usage_events (demo_resume_id);
CREATE INDEX ix_skill_prerequisites_prerequisite_id ON skill_prerequisites (prerequisite_id);
CREATE INDEX ix_candidate_profile_skills_skill_id ON candidate_profile_skills (skill_id);
CREATE INDEX ix_career_profile_embeddings_user_id ON career_profile_embeddings (user_id);
CREATE INDEX ix_resumes_user_id ON resumes (user_id);
CREATE INDEX ix_resumes_template_id ON resumes (template_id);
CREATE INDEX ix_resume_parse_results_resume_id ON resume_parse_results (resume_id);
CREATE INDEX ix_resume_experiences_resume_id ON resume_experiences (resume_id);
CREATE INDEX ix_resume_educations_resume_id ON resume_educations (resume_id);
CREATE INDEX ix_resume_projects_resume_id ON resume_projects (resume_id);
CREATE INDEX ix_resume_certifications_resume_id ON resume_certifications (resume_id);
CREATE INDEX ix_resume_skills_skill_id ON resume_skills (skill_id);
CREATE INDEX ix_resume_analyses_resume_id ON resume_analyses (resume_id);
CREATE INDEX ix_resume_analysis_suggestions_analysis_id ON resume_analysis_suggestions (analysis_id);
CREATE INDEX ix_assessment_sessions_user_id ON assessment_sessions (user_id);
CREATE INDEX ix_assessment_sessions_reused_from_session_id ON assessment_sessions (reused_from_session_id);
CREATE INDEX ix_assessment_questions_session_id ON assessment_questions (session_id);
CREATE INDEX ix_assessment_questions_related_skill_id ON assessment_questions (related_skill_id);
CREATE INDEX ix_assessment_answers_question_id ON assessment_answers (question_id);
CREATE INDEX ix_gap_analysis_skills_gap_analysis_id ON gap_analysis_skills (gap_analysis_id);
CREATE INDEX ix_gap_analysis_skills_skill_id ON gap_analysis_skills (skill_id);
CREATE INDEX ix_roadmaps_user_id ON roadmaps (user_id);
CREATE INDEX ix_roadmaps_market_job_id ON roadmaps (market_job_id);
CREATE INDEX ix_roadmaps_gap_analysis_id ON roadmaps (gap_analysis_id);
CREATE INDEX ix_roadmap_nodes_roadmap_id ON roadmap_nodes (roadmap_id);
CREATE INDEX ix_roadmap_nodes_skill_id ON roadmap_nodes (skill_id);
CREATE INDEX ix_roadmap_node_prerequisites_prerequisite_node_id ON roadmap_node_prerequisites (prerequisite_node_id);
CREATE INDEX ix_market_crawl_items_crawl_run_id ON market_crawl_items (crawl_run_id);
CREATE INDEX ix_market_job_skills_skill_id ON market_job_skills (skill_id);
CREATE INDEX ix_job_matching_runs_user_id ON job_matching_runs (user_id);
CREATE INDEX ix_matching_run_resumes_resume_id ON matching_run_resumes (resume_id);
CREATE INDEX ix_job_match_results_market_job_id ON job_match_results (market_job_id);
CREATE INDEX ix_job_resume_analyses_user_id ON job_resume_analyses (user_id);
CREATE INDEX ix_job_resume_analyses_resume_id ON job_resume_analyses (resume_id);
CREATE INDEX ix_job_resume_analyses_market_job_id ON job_resume_analyses (market_job_id);
CREATE INDEX ix_job_resume_analyses_match_result_id ON job_resume_analyses (match_result_id);
CREATE INDEX ix_application_drafts_user_id ON application_drafts (user_id);
CREATE INDEX ix_application_drafts_market_job_id ON application_drafts (market_job_id);
CREATE INDEX ix_application_drafts_resume_id ON application_drafts (resume_id);
CREATE INDEX ix_external_apply_events_user_id ON external_apply_events (user_id);
CREATE INDEX ix_external_apply_events_market_job_id ON external_apply_events (market_job_id);
CREATE INDEX ix_external_apply_events_resume_id ON external_apply_events (resume_id);
CREATE INDEX ix_career_assistant_conversations_user_id ON career_assistant_conversations (user_id);
CREATE INDEX ix_career_assistant_conversations_guest_session_id ON career_assistant_conversations (guest_session_id);
CREATE INDEX ix_career_assistant_conversations_related_job_id ON career_assistant_conversations (related_job_id);
CREATE INDEX ix_career_assistant_conversations_related_resume_id ON career_assistant_conversations (related_resume_id);
CREATE INDEX ix_career_assistant_conversations_related_gap_analysis_id ON career_assistant_conversations (related_gap_analysis_id);
CREATE INDEX ix_career_assistant_conversations_related_roadmap_id ON career_assistant_conversations (related_roadmap_id);
CREATE INDEX ix_career_assistant_messages_conversation_id ON career_assistant_messages (conversation_id);
CREATE INDEX ix_career_assistant_message_sources_message_id ON career_assistant_message_sources (message_id);
CREATE INDEX ix_career_assistant_message_sources_rag_chunk_id ON career_assistant_message_sources (rag_chunk_id);
CREATE INDEX ix_career_assistant_message_sources_rag_document_id ON career_assistant_message_sources (rag_document_id);
CREATE INDEX ix_career_assistant_feedback_message_id ON career_assistant_feedback (message_id);
CREATE INDEX ix_career_assistant_feedback_user_id ON career_assistant_feedback (user_id);
CREATE INDEX ix_consultation_threads_candidate_user_id ON consultation_threads (candidate_user_id);
CREATE INDEX ix_consultation_threads_consultant_user_id ON consultation_threads (consultant_user_id);
CREATE INDEX ix_consultation_messages_thread_id ON consultation_messages (thread_id);
CREATE INDEX ix_consultation_messages_sender_user_id ON consultation_messages (sender_user_id);
CREATE INDEX ix_user_subscriptions_user_id ON user_subscriptions (user_id);
CREATE INDEX ix_user_subscriptions_tier_id ON user_subscriptions (tier_id);
CREATE INDEX ix_payment_orders_user_id ON payment_orders (user_id);
CREATE INDEX ix_payment_orders_subscription_id ON payment_orders (subscription_id);
CREATE INDEX ix_payment_orders_tier_id ON payment_orders (tier_id);
CREATE INDEX ix_payment_reports_payment_order_id ON payment_reports (payment_order_id);
CREATE INDEX ix_payment_reports_user_id ON payment_reports (user_id);
CREATE INDEX ix_payment_reports_assigned_admin_id ON payment_reports (assigned_admin_id);
CREATE INDEX ix_subscription_renewal_notifications_user_id ON subscription_renewal_notifications (user_id);
CREATE INDEX ix_notifications_user_id ON notifications (user_id);
CREATE INDEX ix_rag_documents_uploaded_by ON rag_documents (uploaded_by);
CREATE INDEX ix_rag_query_logs_user_id ON rag_query_logs (user_id);
CREATE INDEX ix_ai_configurations_updated_by_user_id ON ai_configurations (updated_by_user_id);
CREATE INDEX ix_ai_prompt_templates_updated_by_user_id ON ai_prompt_templates (updated_by_user_id);
CREATE INDEX ix_ai_usage_logs_user_id ON ai_usage_logs (user_id);
CREATE INDEX ix_ai_usage_logs_guest_session_id ON ai_usage_logs (guest_session_id);
CREATE INDEX ix_ai_usage_logs_subscription_id ON ai_usage_logs (subscription_id);
CREATE INDEX ix_announcements_created_by_user_id ON announcements (created_by_user_id);
CREATE INDEX ix_announcement_targets_announcement_id ON announcement_targets (announcement_id);
CREATE INDEX ix_announcement_targets_target_user_id ON announcement_targets (target_user_id);
CREATE INDEX ix_admin_actions_admin_user_id ON admin_actions (admin_user_id);
CREATE INDEX ix_system_logs_user_id ON system_logs (user_id);

