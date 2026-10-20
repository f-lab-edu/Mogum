CREATE TABLE users (
    id bigserial PRIMARY KEY,
    google_sub text NOT NULL UNIQUE,
    email text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'WAITLISTED')),
    role text NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    terms_agreed_at timestamptz,
    terms_version text,
    created_at timestamptz NOT NULL
);
