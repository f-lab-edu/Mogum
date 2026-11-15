DROP TABLE lessons;
DROP TABLE curriculums;
DROP TABLE curriculum_steps;
DROP TABLE curriculum_groups;

CREATE TABLE roadmaps (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    title text NOT NULL,
    description text NOT NULL,
    status text NOT NULL,
    topic text NOT NULL,
    level text NOT NULL,
    level_other text,
    purpose text NOT NULL,
    purpose_other text,
    memo text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT roadmaps_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_title_check CHECK (char_length(title) <= 100),
    CONSTRAINT roadmaps_description_not_blank CHECK (description ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_status_check CHECK (status IN ('REVIEWING', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT roadmaps_topic_not_blank CHECK (topic ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_topic_check CHECK (char_length(topic) <= 100),
    CONSTRAINT roadmaps_level_check CHECK (level IN ('BEGINNER', 'BASICS', 'FAMILIAR', 'OTHER')),
    CONSTRAINT roadmaps_level_other_not_blank CHECK (level_other IS NULL OR level_other ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_level_other_check CHECK (
        ((level = 'OTHER') = (level_other IS NOT NULL)) AND char_length(level_other) <= 50
    ),
    CONSTRAINT roadmaps_purpose_check CHECK (purpose IN ('WORK', 'INTERVIEW', 'GENERAL', 'OTHER')),
    CONSTRAINT roadmaps_purpose_other_not_blank CHECK (purpose_other IS NULL OR purpose_other ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_purpose_other_check CHECK (
        ((purpose = 'OTHER') = (purpose_other IS NOT NULL)) AND char_length(purpose_other) <= 50
    ),
    CONSTRAINT roadmaps_memo_not_blank CHECK (memo IS NULL OR memo ~ '[^[:space:]]'),
    CONSTRAINT roadmaps_memo_check CHECK (char_length(memo) <= 500)
);

CREATE INDEX roadmaps_user_id_idx ON roadmaps(user_id);

CREATE TABLE courses (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    roadmap_id bigint REFERENCES roadmaps(id),
    position integer,
    title text NOT NULL,
    status text NOT NULL,
    topic text,
    level text,
    level_other text,
    purpose text,
    purpose_other text,
    memo text,
    plan_description text,
    plan_concepts jsonb,
    mastery_report jsonb,
    started_at timestamptz,
    last_lesson_opened_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT courses_roadmap_id_position_key UNIQUE (roadmap_id, position),
    CONSTRAINT courses_position_check CHECK (position >= 1),
    CONSTRAINT courses_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT courses_title_check CHECK (char_length(title) <= 100),
    CONSTRAINT courses_status_check CHECK (status IN ('NOT_PLANNED', 'REVIEWING', 'LEARNING', 'COMPLETED')),
    CONSTRAINT courses_topic_not_blank CHECK (topic IS NULL OR topic ~ '[^[:space:]]'),
    CONSTRAINT courses_topic_check CHECK (char_length(topic) <= 100),
    CONSTRAINT courses_level_check CHECK (level IN ('BEGINNER', 'BASICS', 'FAMILIAR', 'OTHER')),
    CONSTRAINT courses_level_other_not_blank CHECK (level_other IS NULL OR level_other ~ '[^[:space:]]'),
    CONSTRAINT courses_level_other_check CHECK (
        ((level = 'OTHER') = (level_other IS NOT NULL)) AND char_length(level_other) <= 50
    ),
    CONSTRAINT courses_purpose_check CHECK (purpose IN ('WORK', 'INTERVIEW', 'GENERAL', 'OTHER')),
    CONSTRAINT courses_purpose_other_not_blank CHECK (purpose_other IS NULL OR purpose_other ~ '[^[:space:]]'),
    CONSTRAINT courses_purpose_other_check CHECK (
        ((purpose = 'OTHER') = (purpose_other IS NOT NULL)) AND char_length(purpose_other) <= 50
    ),
    CONSTRAINT courses_memo_not_blank CHECK (memo IS NULL OR memo ~ '[^[:space:]]'),
    CONSTRAINT courses_memo_check CHECK (char_length(memo) <= 500),
    CONSTRAINT courses_plan_description_not_blank CHECK (plan_description IS NULL OR plan_description ~ '[^[:space:]]'),
    CONSTRAINT courses_roadmap_id_check CHECK (
        (roadmap_id IS NULL AND position IS NULL AND plan_description IS NULL AND plan_concepts IS NULL
            AND topic IS NOT NULL AND level IS NOT NULL AND purpose IS NOT NULL AND status <> 'NOT_PLANNED')
        OR
        (roadmap_id IS NOT NULL AND position IS NOT NULL AND plan_description IS NOT NULL AND plan_concepts IS NOT NULL
            AND topic IS NULL AND level IS NULL AND level_other IS NULL
            AND purpose IS NULL AND purpose_other IS NULL AND memo IS NULL)
    ),
    CONSTRAINT courses_started_at_check CHECK ((status IN ('LEARNING', 'COMPLETED')) = (started_at IS NOT NULL)),
    CONSTRAINT courses_completed_at_check CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX courses_user_id_idx ON courses(user_id);

CREATE TABLE lessons (
    id bigserial PRIMARY KEY,
    course_id bigint NOT NULL REFERENCES courses(id),
    position integer NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    concepts jsonb NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT lessons_course_id_position_key UNIQUE (course_id, position),
    CONSTRAINT lessons_position_check CHECK (position BETWEEN 1 AND 10),
    CONSTRAINT lessons_title_not_blank CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT lessons_description_not_blank CHECK (description ~ '[^[:space:]]')
);

CREATE TABLE materials (
    id bigserial PRIMARY KEY,
    lesson_id bigint NOT NULL REFERENCES lessons(id),
    body text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT materials_lesson_id_key UNIQUE (lesson_id),
    CONSTRAINT materials_body_not_blank CHECK (body ~ '[^[:space:]]')
);

CREATE TABLE cards (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    lesson_id bigint REFERENCES lessons(id) ON DELETE SET NULL,
    position integer NOT NULL,
    question text NOT NULL,
    answer text NOT NULL,
    status text NOT NULL,
    due_at timestamptz,
    fsrs_state text NOT NULL,
    fsrs_step integer,
    stability double precision,
    difficulty double precision,
    last_reviewed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT cards_lesson_id_position_key UNIQUE (lesson_id, position),
    CONSTRAINT cards_question_not_blank CHECK (question ~ '[^[:space:]]'),
    CONSTRAINT cards_answer_not_blank CHECK (answer ~ '[^[:space:]]'),
    CONSTRAINT cards_status_check CHECK (status IN ('ACTIVE', 'COURSE_DELETED')),
    CONSTRAINT cards_lesson_id_check CHECK (status = 'COURSE_DELETED' OR lesson_id IS NOT NULL),
    CONSTRAINT cards_fsrs_state_check CHECK (fsrs_state IN ('LEARNING', 'REVIEW', 'RELEARNING')),
    CONSTRAINT cards_fsrs_step_check CHECK ((fsrs_state = 'REVIEW') = (fsrs_step IS NULL))
);

CREATE INDEX cards_user_id_due_at_idx ON cards(user_id, due_at);
CREATE INDEX cards_lesson_id_idx ON cards(lesson_id);

CREATE TABLE concepts (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    name text NOT NULL,
    definition text NOT NULL,
    embedding vector NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT concepts_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT concepts_definition_not_blank CHECK (definition ~ '[^[:space:]]')
);

CREATE INDEX concepts_user_id_idx ON concepts(user_id);

CREATE TABLE card_concepts (
    card_id bigint NOT NULL REFERENCES cards(id),
    concept_id bigint NOT NULL REFERENCES concepts(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (card_id, concept_id)
);

CREATE INDEX card_concepts_concept_id_idx ON card_concepts(concept_id);

CREATE TABLE reviews (
    id bigserial PRIMARY KEY,
    card_id bigint NOT NULL REFERENCES cards(id),
    rating text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT reviews_rating_check CHECK (rating IN ('AGAIN', 'HARD', 'GOOD', 'EASY'))
);

CREATE INDEX reviews_card_id_idx ON reviews(card_id);
