CREATE TABLE llm_calls (
    id bigserial PRIMARY KEY,
    job_id bigint REFERENCES generation_jobs(id) ON DELETE SET NULL,
    user_id bigint REFERENCES users(id) ON DELETE SET NULL,
    job_type text NOT NULL,
    call_type text NOT NULL,
    model text NOT NULL,
    input_tokens integer,
    output_tokens integer,
    cost numeric,
    duration_ms integer,
    outcome text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT llm_calls_job_type_check CHECK (job_type IN (
        'NEW_COURSE', 'NEW_ROADMAP', 'PLAN_ROADMAP_COURSE', 'REPLAN_COURSE', 'REPLAN_ROADMAP', 'MATERIAL'
    )),
    CONSTRAINT llm_calls_call_type_check CHECK (call_type IN ('CHAT', 'CONTINUATION', 'EMBEDDING')),
    CONSTRAINT llm_calls_model_not_blank CHECK (model ~ '[^[:space:]]'),
    CONSTRAINT llm_calls_outcome_check CHECK (outcome IN (
        'SUCCEEDED', 'TRUNCATED', 'INVALID_OUTPUT', 'TIMEOUT', 'RATE_LIMITED', 'PROVIDER_ERROR', 'REJECTED'
    )),
    CONSTRAINT llm_calls_duration_ms_check CHECK ((outcome IS NULL) = (duration_ms IS NULL))
);

CREATE INDEX llm_calls_created_at_idx ON llm_calls(created_at);
CREATE INDEX llm_calls_job_id_idx ON llm_calls(job_id);
