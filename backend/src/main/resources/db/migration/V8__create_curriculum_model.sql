CREATE TABLE curriculum_groups (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    topic text NOT NULL,
    purpose text,
    title text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT curriculum_groups_topic_not_blank CHECK (topic ~ '[^[:space:]]'),
    CONSTRAINT curriculum_groups_topic_length CHECK (char_length(topic) <= 100),
    CONSTRAINT curriculum_groups_purpose_not_blank CHECK (purpose IS NULL OR purpose ~ '[^[:space:]]'),
    CONSTRAINT curriculum_groups_purpose_length CHECK (purpose IS NULL OR char_length(purpose) <= 200),
    CONSTRAINT curriculum_groups_title_not_blank CHECK (title ~ '[^[:space:]]')
);

CREATE INDEX curriculum_groups_user_id_idx ON curriculum_groups(user_id);

CREATE TABLE curriculum_steps (
    id bigserial PRIMARY KEY,
    group_id bigint NOT NULL REFERENCES curriculum_groups(id),
    order_no integer NOT NULL,
    title text NOT NULL,
    scope text NOT NULL,
    topic text NOT NULL,
    purpose text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT curriculum_steps_group_order_key UNIQUE (group_id, order_no),
    CONSTRAINT curriculum_steps_order_no_check CHECK (order_no >= 1),
    CONSTRAINT curriculum_steps_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT curriculum_steps_scope_not_blank CHECK (scope ~ '[^[:space:]]'),
    CONSTRAINT curriculum_steps_topic_not_blank CHECK (topic ~ '[^[:space:]]'),
    CONSTRAINT curriculum_steps_topic_length CHECK (char_length(topic) <= 100),
    CONSTRAINT curriculum_steps_purpose_not_blank CHECK (purpose IS NULL OR purpose ~ '[^[:space:]]'),
    CONSTRAINT curriculum_steps_purpose_length CHECK (purpose IS NULL OR char_length(purpose) <= 200)
);

CREATE TABLE curriculums (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    topic text NOT NULL,
    purpose text,
    step_id bigint REFERENCES curriculum_steps(id) ON DELETE RESTRICT,
    title text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT curriculums_step_id_key UNIQUE (step_id),
    CONSTRAINT curriculums_topic_not_blank CHECK (topic ~ '[^[:space:]]'),
    CONSTRAINT curriculums_topic_length CHECK (char_length(topic) <= 100),
    CONSTRAINT curriculums_purpose_not_blank CHECK (purpose IS NULL OR purpose ~ '[^[:space:]]'),
    CONSTRAINT curriculums_purpose_length CHECK (purpose IS NULL OR char_length(purpose) <= 200),
    CONSTRAINT curriculums_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT curriculums_status_check CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED'))
);

CREATE INDEX curriculums_user_id_idx ON curriculums(user_id);

CREATE TABLE lessons (
    id bigserial PRIMARY KEY,
    curriculum_id bigint NOT NULL REFERENCES curriculums(id),
    order_no integer NOT NULL,
    title text NOT NULL,
    objective text NOT NULL,
    keywords text[] NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT lessons_curriculum_order_key UNIQUE (curriculum_id, order_no),
    CONSTRAINT lessons_order_no_check CHECK (order_no >= 1),
    CONSTRAINT lessons_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT lessons_objective_not_blank CHECK (objective ~ '[^[:space:]]'),
    CONSTRAINT lessons_status_check CHECK (status IN ('NOT_STARTED', 'READY', 'COMPLETED'))
);
