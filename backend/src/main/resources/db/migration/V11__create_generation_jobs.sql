CREATE TABLE generation_jobs (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    type text NOT NULL,
    status text NOT NULL,
    topic text,
    level text,
    level_other text,
    purpose text,
    purpose_other text,
    memo text,
    feedback text,
    course_id bigint REFERENCES courses(id),
    roadmap_id bigint REFERENCES roadmaps(id),
    lesson_id bigint REFERENCES lessons(id),
    claim_count integer NOT NULL DEFAULT 0,
    lease_until timestamptz,
    error_type text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT generation_jobs_type_check CHECK (type IN (
        'NEW_COURSE', 'NEW_ROADMAP', 'PLAN_ROADMAP_COURSE', 'REPLAN_COURSE', 'REPLAN_ROADMAP', 'MATERIAL'
    )),
    CONSTRAINT generation_jobs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT generation_jobs_topic_not_blank CHECK (topic IS NULL OR topic ~ '[^[:space:]]'),
    CONSTRAINT generation_jobs_topic_check CHECK (char_length(topic) <= 100),
    CONSTRAINT generation_jobs_level_check CHECK (level IN ('BEGINNER', 'BASICS', 'FAMILIAR', 'OTHER')),
    CONSTRAINT generation_jobs_level_other_not_blank CHECK (level_other IS NULL OR level_other ~ '[^[:space:]]'),
    CONSTRAINT generation_jobs_level_other_check CHECK (
        ((level = 'OTHER') = (level_other IS NOT NULL)) AND char_length(level_other) <= 50
    ),
    CONSTRAINT generation_jobs_purpose_check CHECK (purpose IN ('WORK', 'INTERVIEW', 'GENERAL', 'OTHER')),
    CONSTRAINT generation_jobs_purpose_other_not_blank CHECK (purpose_other IS NULL OR purpose_other ~ '[^[:space:]]'),
    CONSTRAINT generation_jobs_purpose_other_check CHECK (
        ((purpose = 'OTHER') = (purpose_other IS NOT NULL)) AND char_length(purpose_other) <= 50
    ),
    CONSTRAINT generation_jobs_memo_not_blank CHECK (memo IS NULL OR memo ~ '[^[:space:]]'),
    CONSTRAINT generation_jobs_memo_check CHECK (char_length(memo) <= 500),
    CONSTRAINT generation_jobs_input_check CHECK (
        (type IN ('NEW_COURSE', 'NEW_ROADMAP') AND topic IS NOT NULL AND level IS NOT NULL AND purpose IS NOT NULL)
        OR
        (type NOT IN ('NEW_COURSE', 'NEW_ROADMAP') AND topic IS NULL AND level IS NULL AND level_other IS NULL
            AND purpose IS NULL AND purpose_other IS NULL AND memo IS NULL)
    ),
    CONSTRAINT generation_jobs_feedback_not_blank CHECK (feedback IS NULL OR feedback ~ '[^[:space:]]'),
    CONSTRAINT generation_jobs_feedback_length_check CHECK (char_length(feedback) <= 500),
    CONSTRAINT generation_jobs_feedback_check CHECK (type IN ('REPLAN_COURSE', 'REPLAN_ROADMAP') OR feedback IS NULL),
    CONSTRAINT generation_jobs_target_count_check CHECK (num_nonnulls(course_id, roadmap_id, lesson_id) <= 1),
    CONSTRAINT generation_jobs_target_type_check CHECK (
        CASE type
            WHEN 'NEW_COURSE' THEN
                ((status = 'SUCCEEDED') = (course_id IS NOT NULL)) AND roadmap_id IS NULL AND lesson_id IS NULL
            WHEN 'NEW_ROADMAP' THEN
                ((status = 'SUCCEEDED') = (roadmap_id IS NOT NULL)) AND course_id IS NULL AND lesson_id IS NULL
            WHEN 'PLAN_ROADMAP_COURSE' THEN course_id IS NOT NULL AND roadmap_id IS NULL AND lesson_id IS NULL
            WHEN 'REPLAN_COURSE' THEN course_id IS NOT NULL AND roadmap_id IS NULL AND lesson_id IS NULL
            WHEN 'REPLAN_ROADMAP' THEN roadmap_id IS NOT NULL AND course_id IS NULL AND lesson_id IS NULL
            WHEN 'MATERIAL' THEN lesson_id IS NOT NULL AND course_id IS NULL AND roadmap_id IS NULL
            ELSE TRUE -- Unknown types are rejected by generation_jobs_type_check.
        END
    ),
    CONSTRAINT generation_jobs_claim_count_check CHECK (claim_count >= 0),
    CONSTRAINT generation_jobs_lease_until_check CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CONSTRAINT generation_jobs_error_type_check CHECK ((status = 'FAILED') = (error_type IS NOT NULL)),
    CONSTRAINT generation_jobs_error_type_values_check CHECK (
        error_type IN ('PROVIDER_UNAVAILABLE', 'INVALID_OUTPUT', 'PROVIDER_REJECTED', 'INPUT_TOO_LARGE')
    )
);

CREATE INDEX generation_jobs_status_created_at_idx ON generation_jobs(status, created_at);
CREATE INDEX generation_jobs_user_id_status_idx ON generation_jobs(user_id, status);

CREATE UNIQUE INDEX generation_jobs_material_lesson_id_key ON generation_jobs(lesson_id)
    WHERE type = 'MATERIAL';
CREATE UNIQUE INDEX generation_jobs_plan_roadmap_course_course_id_key ON generation_jobs(course_id)
    WHERE type = 'PLAN_ROADMAP_COURSE';
CREATE UNIQUE INDEX generation_jobs_replan_course_course_id_key ON generation_jobs(course_id)
    WHERE type = 'REPLAN_COURSE' AND status <> 'SUCCEEDED';
CREATE UNIQUE INDEX generation_jobs_replan_roadmap_roadmap_id_key ON generation_jobs(roadmap_id)
    WHERE type = 'REPLAN_ROADMAP' AND status <> 'SUCCEEDED';

ALTER TABLE credit_entries
    ADD COLUMN job_id bigint REFERENCES generation_jobs(id) ON DELETE SET NULL;

CREATE INDEX credit_entries_job_id_idx ON credit_entries(job_id);
